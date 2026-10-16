package io.netherite.security.rate;

import io.netherite.common.context.TenantContext;
import io.netherite.security.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity tier ({@code @Order(4)}) verification: per-user and per-organization buckets are
 * enforced exclusively from server-derived {@link TenantContextHolder} identities (never from
 * client headers), quotas never leak across tenants, and a full-tenancy org bucket bounds the
 * total a tenant can push through regardless of how many users it fans out across.
 *
 * <h2>Why this drives the filter directly instead of going through HTTP</h2>
 *
 * <p>The identity buckets under test are declared on {@code /v1/dashboard/**} in
 * {@code application-ratelimit.yml} (USER: 2, ORGANIZATION: 5 per minute). Measuring the
 * per-user and per-organization interaction needs several distinct principals of the <em>same</em>
 * organization to reach that surface concurrently.</p>
 *
 * <p>Under WS6 that is no longer possible over HTTP: every governed {@code /v1} surface is
 * reachable only by the organization's single designated authorized person, so a second member of
 * the same organization is refused by {@code AuthorizationFilter} with 403 long before the
 * identity tier is consulted. The old fixture encoded the pre-WS6 world of many equally
 * privileged administrators per tenant.</p>
 *
 * <p>These tests therefore exercise the real {@link IdentityRateLimitFilter} with the real
 * {@link RateLimitService} and the real route configuration, supplying the authenticated
 * {@link TenantContext} that the authentication filters would have established. That keeps the
 * subject of the test — how buckets are keyed and bounded — completely intact, and removes an
 * incidental coupling to the authorization model that WS6 invalidated. Authorization itself is
 * covered by {@code AuthorizationMatrixSecurityTest} and
 * {@code ManagementEndpointAuthenticationTest}.
 */
@SpringBootTest
@ActiveProfiles({"test", "ratelimit"})
class IdentityRateLimitFilterIntegrationTest {

    /** Matches the USER/ORGANIZATION identity buckets configured for the dashboard surface. */
    private static final String MEASURED_PATH = "/v1/dashboard";
    private static final int USER_LIMIT = 2;
    private static final int ORGANIZATION_LIMIT = 5;

    @Autowired private IdentityRateLimitFilter identityRateLimitFilter;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Per-user budget denies the user, then a different user in the same org still proceeds")
    void perUserBucketIsolatedWithinOrg() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        assertAllowed(userA, orgId);
        assertAllowed(userA, orgId);
        assertDenied(userA, orgId);

        // The shared organization budget was only tapped twice; user B is untouched.
        assertAllowed(userB, orgId);
    }

    @Test
    @DisplayName("Organization bucket bounds the whole tenant no matter how many users fan out")
    void organizationBucketBoundsTenant() throws Exception {
        UUID orgId = UUID.randomUUID();

        // Five distinct users, one request each: the org budget is now exhausted.
        for (int i = 0; i < ORGANIZATION_LIMIT; i++) {
            assertAllowed(UUID.randomUUID(), orgId);
        }

        // A sixth, brand-new user in the SAME org is denied by the ORGANIZATION bucket,
        // even though that user's personal budget is completely untouched.
        UUID sixth = UUID.randomUUID();
        assertDenied(sixth, orgId);
    }

    @Test
    @DisplayName("Exhaustion in one tenant never affects another tenant")
    void crossTenantIsolation() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        // Exhaust user A's personal budget; the shared org budget is only tapped twice.
        assertAllowed(userA, tenantA);
        assertAllowed(userA, tenantA);
        assertDenied(userA, tenantA);

        // tenant B has its own org bucket and user bucket: fully admitted.
        assertAllowed(UUID.randomUUID(), tenantB);
    }

    @Test
    @DisplayName("A spoofed client header never selects another identity's bucket")
    void spoofedHeaderDoesNotSelectBucket() throws Exception {
        UUID victimOrg = UUID.randomUUID();
        UUID attackerOrg = UUID.randomUUID();
        UUID attackerUser = UUID.randomUUID();

        // Exhaust the victim's organization budget entirely.
        for (int i = 0; i < ORGANIZATION_LIMIT; i++) {
            assertAllowed(UUID.randomUUID(), victimOrg);
        }
        assertDenied(UUID.randomUUID(), victimOrg);

        // The attacker, in a different tenant, presents the victim's identifiers in
        // headers. Buckets are keyed only on the server-derived context, so the
        // attacker's own (untouched) budget still admits the request.
        assertAllowed(attackerUser, attackerOrg, Map.of(
                "X-Organization-Id", victimOrg.toString(),
                "X-User-Id", UUID.randomUUID().toString(),
                "X-Netherite-Agent-Key", "ntk_live_spoofed.attackerkey"));
    }

    private void assertAllowed(UUID userId, UUID orgId) throws Exception {
        assertAllowed(userId, orgId, Map.of());
    }

    private void assertAllowed(UUID userId, UUID orgId, Map<String, String> headers) throws Exception {
        MockHttpServletResponse response = dispatch(userId, orgId, headers);
        assertEquals(200, response.getStatus(),
                "expected the identity tier to admit the request, body=" + response.getContentAsString());
    }

    private void assertDenied(UUID userId, UUID orgId) throws Exception {
        MockHttpServletResponse response = dispatch(userId, orgId, Map.of());
        assertEquals(429, response.getStatus(),
                "expected the identity tier to deny the request, body=" + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("ERR_RATE_LIMIT_EXCEEDED"),
                "denial body must carry the canonical error code");
    }

    /**
     * Drives the real filter with the authenticated context an authentication filter
     * would have established, and returns the response the filter produced.
     */
    private MockHttpServletResponse dispatch(UUID userId, UUID orgId, Map<String, String> headers)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", MEASURED_PATH);
        headers.forEach(request::addHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};
        FilterChain chain = (req, res) -> chainInvoked[0] = true;

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(orgId.toString())
                .userId(userId.toString())
                .principalType("USER")
                .build());
        try {
            identityRateLimitFilter.doFilter(request, response, chain);
        } finally {
            TenantContextHolder.clear();
        }

        return response;
    }
}
