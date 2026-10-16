package io.netherite.security;

import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.auth.session.SessionTokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch F â€” actuator is gated at the origin (Batch F scope: actuator hardening).
 *
 * <p>Wiring: {@code ActuatorAccessFilter} (order 4) runs for every /actuator
 * request, keeps {@code /actuator/health**} public and requires a human session
 * for the organization's designated authorized person for everything else. The health/readiness/liveness
 * probes must stay open so orchestrators, the compose healthchecks and the edge
 * never need credentials.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.endpoint.health.probes.enabled=true"
})
class ActuatorAccessFilterTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private OrganizationEntity organization;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization(
                "BatchF Corp", "batchf-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Mints a session for a member, designating them as the organization's single
     * authorized person (WS6). Passing {@code designate=false} mints a session
     * for an ordinary, non-designated member.
     */
    private Cookie mintSession(String label, boolean designate) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email(label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Hello " + label)
                .status("ACTIVE")
                .build());
        if (designate) {
            organization.setAuthorizedPersonUserId(user.getId());
            organizationRepository.save(organization);
            organizationRepository.flush();
        }
        String rawToken = "batchf-" + label + "-" + UUID.randomUUID();
        userSessionRepository.save(UserSessionEntity.builder()
                .userId(user.getId())
                .organizationId(organization.getId())
                .sessionToken(SessionTokenHasher.hash(rawToken))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());
        return new Cookie("neth_sid", rawToken);
    }

    @Test
    @DisplayName("BatchF-A1. /actuator/health is public (no session, no 401/403)")
    void testHealthPublic() throws Exception {
        // The aggregate status may legitimately be 503 when an optional dependency
        // indicator (e.g. OPA) is down in the gate environment; what matters is
        // that no credential is ever demanded.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("public health endpoint must not demand credentials, got " + status);
                    }
                });
    }

    @Test
    @DisplayName("BatchF-A2. /actuator/health/readiness probe is public")
    void testReadinessProbePublic() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BatchF-A3. /actuator/health/liveness probe is public")
    void testLivenessProbePublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BatchF-A4. /actuator/metrics without a session -> 401")
    void testMetricsUnauthenticated() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("BatchF-A5. /actuator/info without a session -> 401")
    void testInfoUnauthenticated() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("BatchF-A6. /actuator/metrics with the designated person's session -> 200")
    void testMetricsWithAdminSession() throws Exception {
        mockMvc.perform(get("/actuator/metrics").cookie(mintSession("designated", true)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BatchF-A7. /actuator/info with the designated person's session -> 200")
    void testInfoWithAdminSession() throws Exception {
        mockMvc.perform(get("/actuator/info").cookie(mintSession("designated", true)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BatchF-A8. /actuator/metrics with a non-designated member's session -> 403")
    void testMetricsWithNonAdminSession() throws Exception {
        mockMvc.perform(get("/actuator/metrics").cookie(mintSession("ordinary-member", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
    }

    @Test
    @DisplayName("BatchF-A9. /actuator/metrics with an expired session -> 401")
    void testMetricsWithExpiredSession() throws Exception {
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("expired-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Expired")
                .status("ACTIVE")
                .build());
        String rawToken = "batchf-expired-" + UUID.randomUUID();
        userSessionRepository.save(UserSessionEntity.builder()
                .userId(user.getId())
                .organizationId(organization.getId())
                .sessionToken(SessionTokenHasher.hash(rawToken))
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());

        mockMvc.perform(get("/actuator/metrics").cookie(new Cookie("neth_sid", rawToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("BatchF-A10. /actuator/env is gated at the origin; even the designated person sees 404 (not exposed)")
    void testEnvNotExposed() throws Exception {
        // Unauthenticated: the origin requires an admin session BEFORE any
        // endpoint mapping, so even a non-existent/excluded management path does
        // not disclose its existence to anonymous callers.
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());

        // Authenticated admin: the excluded endpoint truly does not exist -> 404.
        mockMvc.perform(get("/actuator/env").cookie(mintSession("designated", true)))
                .andExpect(status().isNotFound());
    }
}