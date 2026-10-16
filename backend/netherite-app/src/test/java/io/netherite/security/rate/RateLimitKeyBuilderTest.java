package io.netherite.security.rate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitKeyBuilderTest {

    @Test
    @DisplayName("Keys are deterministic and namespaced")
    void keysAreDeterministic() {
        RateLimitProperties.RouteSpec route = route("owner-login");
        RateLimitKeyBuilder a = builder("owner-login", "203.0.113.7", "Owner@Example.com");
        RateLimitKeyBuilder b = builder("owner-login", "203.0.113.7", "Owner@Example.com");

        assertEquals("netherite:rl:owner-login:ip:203.0.113.7",
                a.bucketKey(route, RateLimitDimension.IP));

        assertEquals(b.bucketKey(route, RateLimitDimension.IP),
                a.bucketKey(route, RateLimitDimension.IP));
        assertEquals(b.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT),
                a.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT));
        assertEquals(b.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT_IP),
                a.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT_IP));
    }

    @Test
    @DisplayName("Accounts never appear raw in keys: normalized and SHA-256 hashed")
    void accountIsHashedAndNormalized() {
        RateLimitProperties.RouteSpec route = route("owner-login");
        RateLimitKeyBuilder a = builder("owner-login", "203.0.113.7", " Owner@Example.com ");
        RateLimitKeyBuilder b = builder("owner-login", "203.0.113.7", "owner@example.com");

        String keyA = a.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT);
        String keyB = b.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT);

        assertEquals(keyA, keyB);
        assertFalse(keyA.contains("owner@"), "raw account must not leak into the key");
        assertFalse(keyA.contains("Owner@Example.com"));
        assertFalse(keyA.contains("example.com"));
        assertTrue(keyA.matches(".*login-account:[0-9a-f]{64}"));
        // Per-IP variant is deterministic for the same hashed account + IP
        String ipVariant = a.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT_IP);
        assertTrue(ipVariant.matches(".*login-account-ip:[0-9a-f]{64}:203\\.0\\.113\\.7"));
        assertEquals(ipVariant, b.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT_IP));
    }

    @Test
    @DisplayName("API-key identifiers are hashed; secrets/credentials never enter a key")
    void agentKeyIdentifierIsHashedNotStored() {
        RateLimitKeyBuilder builder = RateLimitKeyBuilder.builder()
                .ip("198.51.100.9")
                .agentKeyIdentifier("kid-123")
                .build();

        String key = builder.bucketKey(route("agent-execution"), RateLimitDimension.AGENT_KEY_IDENTIFIER);
        assertTrue(key.matches(".*agent-key:[0-9a-f]{64}"));
        assertFalse(key.contains("kid-123"));
    }

    @Test
    @DisplayName("Identity dimensions use the raw server-derived ids and never a client header value")
    void identityDimensionsAreTransparentIds() {
        RateLimitKeyBuilder builder = RateLimitKeyBuilder.builder()
                .organizationId("org-uuid")
                .userId("user-uuid")
                .agentId("agent-uuid")
                .build();

        assertEquals("netherite:rl:agent-execution:org:org-uuid",
                builder.bucketKey(route("agent-execution"), RateLimitDimension.ORGANIZATION));
        assertEquals("netherite:rl:approvals:user:user-uuid",
                builder.bucketKey(route("approvals"), RateLimitDimension.USER));
        assertEquals("netherite:rl:gateway:agent:agent-uuid",
                builder.bucketKey(route("gateway"), RateLimitDimension.AGENT));
    }

    @Test
    @DisplayName("Unresolvable dimensions produce no key")
    void unresolvedDimensionYieldsNullKey() {
        RateLimitKeyBuilder builder = RateLimitKeyBuilder.builder().ip("1.2.3.4").build();
        assertNotNull(builder.bucketKey(route("login"), RateLimitDimension.IP));
        assertNull(builder.bucketKey(route("login"), RateLimitDimension.LOGIN_ACCOUNT));
        assertNull(builder.bucketKey(route("login"), RateLimitDimension.LOGIN_ACCOUNT_IP));
        assertNull(builder.bucketKey(route("login"), RateLimitDimension.ORGANIZATION));
    }

    @Test
    @DisplayName("The account-IP key binds the hash to a specific source, distinct from the global account key")
    void accountIpIsDistinctFromAccount() {
        RateLimitKeyBuilder builder = RateLimitKeyBuilder.builder()
                .ip("198.51.100.4")
                .account("a@example.com")
                .build();
        RateLimitProperties.RouteSpec route = route("login");
        String account = builder.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT);
        String accountIp = builder.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT_IP);
        assertNotEquals(account, accountIp);

        RateLimitKeyBuilder otherIp = RateLimitKeyBuilder.builder()
                .ip("198.51.100.5")
                .account("a@example.com")
                .build();
        assertNotEquals(accountIp, otherIp.bucketKey(route, RateLimitDimension.LOGIN_ACCOUNT_IP));
    }

    private static RateLimitKeyBuilder builder(String routeName, String ip, String account) {
        return RateLimitKeyBuilder.builder().ip(ip).account(account).build();
    }

    private static RateLimitProperties.RouteSpec route(String name) {
        RateLimitProperties.RouteSpec route = new RateLimitProperties.RouteSpec();
        route.setName(name);
        return route;
    }
}