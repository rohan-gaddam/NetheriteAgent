package io.netherite.security.rate;

import java.util.List;

/**
 * Backend that holds the rate-limit counters and answers admission decisions atomically.
 *
 * <p>Two provider implementations mirror the distributed coordination pattern:
 * <ul>
 *   <li>{@link RedisRateLimitStore} — Lua fixed-window counters in Redis, atomic across every
 *       application replica (the production provider);</li>
 *   <li>{@link InMemoryRateLimitStore} — single-node counters for tests and non-distributed
 *       deployments.</li>
 * </ul>
 *
 * <p>Both providers implement identical semantics: every {@link BucketKey} is incremented unless
 * <em>any</em> bucket is already over its limit, in which case no request quota is consumed and the
 * request is denied (all increments are rolled back). Windows are fixed windows anchored at the
 * first observed request, and each key is bound by a TTL equal to its window, so the key space is
 * always bounded.</p>
 */
public interface RateLimitStore {

    /**
     * Atomically evaluates all buckets for one request.
     *
     * @param buckets the keys to increment and their limits
     * @return {@code allow()} when every bucket is below its limit, otherwise {@code deny(retry)} 
     *         with the shortest remaining window across the exceeded buckets
     */
    RateLimitDecision checkAndIncrement(List<BucketKey> buckets);

    record BucketKey(String key, int limit, long windowSeconds) {
    }
}