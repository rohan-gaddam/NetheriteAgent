package io.netherite.security.rate;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed rate-limit store backed by a single atomic Lua script over the existing Redis broker.
 *
 * <p>Every request evaluates <em>all</em> of its buckets in one atomic script execution: counters
 * are incremented, and if any bucket exceeds its limit all increments are rolled back so an
 * over-limit request never consumes quota. Windows are fixed and anchored at the first hit
 * ({@code PEXPIRE} on {@code INCR == 1}), so each key's TTL equals its window — the Redis key
 * space is always bounded and self-cleaning. Because the script runs inside Redis, decisions are
 * identical across every application replica (no per-node bypass).</p>
 *
 * <p>Failure surfaces as an exception propagated to {@link RateLimitService}, which applies the
 * route's fail-closed/fail-open policy — the control plane never silently falls back to
 * per-node-only limiting.</p>
 */
@Component
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
        havingValue = "redis", matchIfMissing = true)
public class RedisRateLimitStore implements RateLimitStore {

    /**
     * One atomic evaluation for a variable number of buckets.
     *
     * <p>KEYS[n] = bucket keys; ARGV pairs = (limit, windowSeconds) per key.
     * Returns {@code {denied, soonestPttlMs}}.</p>
     */
    private static final String LUA_FIXED_WINDOW = """
            local bucketCount = #KEYS
            local denied = 0
            local soonestPttl = -1
            for i = 1, bucketCount do
                local limit = tonumber(ARGV[(i - 1) * 2 + 1])
                local windowSeconds = tonumber(ARGV[(i - 1) * 2 + 2])
                local current = redis.call('INCR', KEYS[i])
                if current == 1 then
                    redis.call('PEXPIRE', KEYS[i], windowSeconds * 1000)
                end
                if current > limit then
                    denied = 1
                    local pttl = redis.call('PTTL', KEYS[i])
                    if pttl > 0 and (soonestPttl < 0 or pttl < soonestPttl) then
                        soonestPttl = pttl
                    end
                end
            end
            if denied == 1 then
                for i = 1, bucketCount do
                    redis.call('DECR', KEYS[i])
                end
            end
            return {denied, soonestPttl}
            """;

    private final RedissonClient redissonRateLimitClient;

    public RedisRateLimitStore(@Lazy @Qualifier("redissonRateLimitClient") RedissonClient redissonRateLimitClient) {
        this.redissonRateLimitClient = redissonRateLimitClient;
    }

    @Override
    public RateLimitDecision checkAndIncrement(List<BucketKey> buckets) {
        if (buckets.isEmpty()) {
            return RateLimitDecision.allow();
        }

        List<Object> keys = new ArrayList<>(buckets.size());
        List<Object> values = new ArrayList<>(buckets.size() * 2);
        for (BucketKey bucket : buckets) {
            keys.add(bucket.key());
            values.add(bucket.limit());
            values.add(bucket.windowSeconds());
        }

        List<?> result = (List<?>) redissonRateLimitClient.getScript(StringCodec.INSTANCE)
                .eval(RScript.Mode.READ_WRITE, LUA_FIXED_WINDOW,
                        RScript.ReturnType.MULTI, keys, values.toArray());

        long denied = ((Number) result.get(0)).longValue();
        if (denied == 1L) {
            long soonestPttlMs = ((Number) result.get(1)).longValue();
            int retryAfter = soonestPttlMs <= 0
                    ? 1
                    : Math.max(1, (int) Math.ceil(soonestPttlMs / 1000.0));
            return RateLimitDecision.deny(retryAfter);
        }
        return RateLimitDecision.allow();
    }
}