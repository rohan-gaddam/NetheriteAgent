package io.netherite.security.rate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-Redis verification of the atomic fixed-window Lua store (profile {@code distributed}).
 * Requires an actual broker (Docker compose in the live phase); the *IT suffix keeps it out of the
 * ordinary surefire gate — see prerequisite C in the WS5 ledger. Every test uses freshly generated
 * keys and deletes them afterwards so runs are repeatable and the key space stays bounded.
 */
@SpringBootTest
@ActiveProfiles({"test", "distributed"})
class RedisRateLimitStoreIT {

    @Autowired private RedisRateLimitStore store;
    @Autowired private RedissonClient redissonRateLimitClient;

    private final List<String> keysToClean = new java.util.ArrayList<>();

    private RateLimitStore.BucketKey bucket(String suffix, int limit, long windowSeconds) {
        String key = "netherite:rl:it:" + UUID.randomUUID().toString().substring(0, 8) + ":" + suffix;
        keysToClean.add(key);
        return new RateLimitStore.BucketKey(key, limit, windowSeconds);
    }

    @AfterEach
    void cleanUp() {
        if (!keysToClean.isEmpty()) {
            redissonRateLimitClient.getKeys().delete(keysToClean.toArray(new String[0]));
            keysToClean.clear();
        }
    }

    @Test
    @DisplayName("Budget is enforced atomically and identically in a real broker")
    void budgetEnforcedAgainstRealRedis() {
        RateLimitStore.BucketKey key = bucket("ip", 3, 60);
        assertTrue(store.checkAndIncrement(List.of(key)).allowed());
        assertTrue(store.checkAndIncrement(List.of(key)).allowed());
        assertTrue(store.checkAndIncrement(List.of(key)).allowed());
        assertFalse(store.checkAndIncrement(List.of(key)).allowed(),
                "the fourth execution on the SAME key must be denied");
    }

    @Test
    @DisplayName("An over-limit sibling bucket rolls back all counters in the same script run")
    void overLimitSiblingRollsBackTheWholeDecision() {
        RateLimitStore.BucketKey ipBucket = bucket("ip", 2, 60);
        RateLimitStore.BucketKey accountBucket = bucket("acct", 1, 60);

        RateLimitStore.BucketKey[] batch1 = {ipBucket, accountBucket};
        assertTrue(store.checkAndIncrement(List.of(batch1)).allowed(), "first: both counters set to 1");

        assertFalse(store.checkAndIncrement(List.of(batch1)).allowed(),
                "second: account exceeds 1 so the whole execution denies");

        // The IP counter was rolled back, so a subsequent standalone check still fits its budget.
        assertTrue(store.checkAndIncrement(List.of(ipBucket)).allowed(),
                "IP counter must have been rolled back (otherwise it would now be 3 > 2)");
    }

    @Test
    @DisplayName("Window resets the counter after expiry (fixed window anchored at first hit)")
    void windowExpiryResetsCounter() throws Exception {
        RateLimitStore.BucketKey key = bucket("window", 3, 1);
        for (int i = 0; i < 3; i++) {
            assertTrue(store.checkAndIncrement(List.of(key)).allowed());
        }
        assertFalse(store.checkAndIncrement(List.of(key)).allowed());

        Thread.sleep(1100);
        assertTrue(store.checkAndIncrement(List.of(key)).allowed(),
                "after the 1s window expires the counter resets");
    }

    @Test
    @DisplayName("Every bucket key carries a TTL equal to its window (bounded key space)")
    void keysAreBoundedByWindowTtl() {
        RateLimitStore.BucketKey key = bucket("ttl", 5, 60);
        assertTrue(store.checkAndIncrement(List.of(key)).allowed());
        long ttlMs = redissonRateLimitClient.getBucket(key.key()).remainTimeToLive();
        assertTrue(ttlMs > 0, "key must carry a TTL");
        assertTrue(ttlMs <= 61_000, "TTL must be bounded by the window: " + ttlMs);
    }

    @Test
    @DisplayName("Empty evaluation is a no-op allow")
    void emptyBucketsAllow() {
        assertTrue(store.checkAndIncrement(List.of()).allowed());
    }

    @Test
    @DisplayName("Denied executions report the soonest retry-after for the throttled bucket")
    void deniedReportsRetryAfter() {
        RateLimitStore.BucketKey retryKey = bucket("retry", 1, 60);
        assertTrue(store.checkAndIncrement(List.of(retryKey)).allowed());

        RateLimitStore.BucketKey freshKey = bucket("other", 1, 60);
        RateLimitDecision denied = store.checkAndIncrement(List.of(freshKey, retryKey));
        assertFalse(denied.allowed());
        // The throttled key still has most of its 60 s window left -> retry-after is that
        // remaining window, sanitised to at least 1 s. Never 0.
        assertTrue(denied.retryAfterSeconds() >= 1 && denied.retryAfterSeconds() <= 60,
                "retry-after must mirror the remaining window: " + denied.retryAfterSeconds());
    }
}