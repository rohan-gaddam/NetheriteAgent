package io.netherite.security.rate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    private static RateLimitProperties propertiesWithRoutes(List<RateLimitProperties.RouteSpec> routes) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setRoutes(routes);
        return properties;
    }

    private static RateLimitProperties.RouteSpec route(String name, String path,
                                                       List<String> methods, boolean failClosed,
                                                       RateLimitProperties.BucketSpec... buckets) {
        RateLimitProperties.RouteSpec spec = new RateLimitProperties.RouteSpec();
        spec.setName(name);
        spec.setPath(path);
        spec.setMethods(methods == null ? List.of() : methods);
        spec.setFailClosed(failClosed);
        spec.setBuckets(List.of(buckets));
        return spec;
    }

    private static RateLimitProperties.BucketSpec bucket(RateLimitDimension dimension, int limit, long window) {
        RateLimitProperties.BucketSpec spec = new RateLimitProperties.BucketSpec();
        spec.setDimension(dimension);
        spec.setLimit(limit);
        spec.setWindowSeconds(window);
        return spec;
    }

    @Test
    @DisplayName("First matching route wins (firewall semantics, method-aware)")
    void firstMatchingRouteWins() {
        RateLimitProperties.RouteSpec login = route("owner-login", "/v1/auth/login",
                List.of("POST"), true, bucket(RateLimitDimension.IP, 1, 60));
        RateLimitProperties.RouteSpec catchAll = route("oidc", "/v1/auth/oidc/**",
                List.of(), true, bucket(RateLimitDimension.IP, 2, 60));

        RateLimitService service = new RateLimitService(propertiesWithRoutes(List.of(login, catchAll)),
                new RateLimitDependencyGuard(propertiesWithRoutes(List.of())),
                new InMemoryRateLimitStore());

        assertEquals("owner-login", service.matchRoute("POST", "/v1/auth/login").getName());
        assertNull(service.matchRoute("GET", "/v1/auth/login"));
        assertEquals("oidc", service.matchRoute("GET", "/v1/auth/oidc/start").getName());
        assertNull(service.matchRoute("PUT", "/v1/nonexistent"));
    }

    @Test
    @DisplayName("Pre-auth IP bucket denies after the limit")
    void preAuthIpDeniesAfterLimit() {
        RateLimitProperties.RouteSpec login = route("owner-login", "/v1/auth/login",
                List.of("POST"), true, bucket(RateLimitDimension.IP, 2, 60));

        RateLimitService service = new RateLimitService(propertiesWithRoutes(List.of(login)),
                new RateLimitDependencyGuard(propertiesWithRoutes(List.of())),
                new InMemoryRateLimitStore());

        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluatePreAuth("POST", "/v1/auth/login", "10.0.0.1", null, null).decision());
        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluatePreAuth("POST", "/v1/auth/login", "10.0.0.1", null, null).decision());
        RateLimitVerdict third = service.evaluatePreAuth("POST", "/v1/auth/login", "10.0.0.1", null, null);
        assertEquals(RateLimitVerdict.Decision.DENY, third.decision());
        assertTrue(third.retryAfterSeconds() >= 1);
    }

    @Test
    @DisplayName("A different source IP has its own bucket")
    void ipKeysAreIsolated() {
        RateLimitProperties.RouteSpec login = route("owner-login", "/v1/auth/login",
                List.of("POST"), true, bucket(RateLimitDimension.IP, 1, 60));

        RateLimitService service = new RateLimitService(propertiesWithRoutes(List.of(login)),
                new RateLimitDependencyGuard(propertiesWithRoutes(List.of())),
                new InMemoryRateLimitStore());

        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluatePreAuth("POST", "/v1/auth/login", "10.0.0.1", null, null).decision());
        assertEquals(RateLimitVerdict.Decision.DENY,
                service.evaluatePreAuth("POST", "/v1/auth/login", "10.0.0.1", null, null).decision());
        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluatePreAuth("POST", "/v1/auth/login", "10.0.0.2", null, null).decision());
    }

    @Test
    @DisplayName("Fail-open is the default when the store cannot decide")
    void failOpenOnStoreFailure() {
        RateLimitProperties.RouteSpec route = route("read", "/v1/audit/**",
                List.of("GET"), false, bucket(RateLimitDimension.USER, 5, 60));
        RateLimitProperties properties = propertiesWithRoutes(List.of(route));

        RateLimitStore down = buckets -> {
            throw new RuntimeException("redis unavailable");
        };

        RateLimitService service = new RateLimitService(properties,
                new RateLimitDependencyGuard(properties), down);

        io.netherite.common.context.TenantContext ctx = context("org-1", "user-1", null);
        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluateIdentity("GET", "/v1/audit/events", ctx).decision());
    }

    @Test
    @DisplayName("Security-sensitive fail-closed routes deny with DEPENDENCY_UNAVAILABLE when the store cannot decide")
    void failClosedOnStoreFailure() {
        RateLimitProperties.RouteSpec route = route("execute", "/v1/actions/execute",
                List.of("POST"), true, bucket(RateLimitDimension.ORGANIZATION, 100, 3600));
        RateLimitProperties properties = propertiesWithRoutes(List.of(route));

        RateLimitStore down = buckets -> {
            throw new RuntimeException("redis unavailable");
        };

        RateLimitService service = new RateLimitService(properties,
                new RateLimitDependencyGuard(properties), down);

        io.netherite.common.context.TenantContext ctx = context("org-1", null, "agent-1");
        assertEquals(RateLimitVerdict.Decision.DEPENDENCY_UNAVAILABLE,
                service.evaluateIdentity("POST", "/v1/actions/execute", ctx).decision());
    }

    @Test
    @DisplayName("Identity tiers key on organization and user separately")
    void identityTiersSeparateOrgFromUser() {
        RateLimitProperties.RouteSpec route = route("dashboard", "/v1/dashboard/**",
                List.of(), false,
                bucket(RateLimitDimension.USER, 1, 60),
                bucket(RateLimitDimension.ORGANIZATION, 10, 60));

        RateLimitService service = new RateLimitService(propertiesWithRoutes(List.of(route)),
                new RateLimitDependencyGuard(propertiesWithRoutes(List.of())),
                new InMemoryRateLimitStore());

        io.netherite.common.context.TenantContext userA = context("org-1", "user-a", null);
        io.netherite.common.context.TenantContext userB = context("org-1", "user-b", null);

        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluateIdentity("GET", "/v1/dashboard/x", userA).decision());
        assertEquals(RateLimitVerdict.Decision.DENY,
                service.evaluateIdentity("GET", "/v1/dashboard/x", userA).decision());
        // user bucket is per-user: user B is still allowed within the org bucket budget
        assertEquals(RateLimitVerdict.Decision.ALLOW,
                service.evaluateIdentity("GET", "/v1/dashboard/x", userB).decision());
    }

    private static io.netherite.common.context.TenantContext context(String orgId, String userId, String agentId) {
        return io.netherite.common.context.TenantContext.builder()
                .organizationId(orgId)
                .userId(userId)
                .agentId(agentId)
                .build();
    }
}