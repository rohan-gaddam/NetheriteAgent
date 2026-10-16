package io.netherite.security.rate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-node rate-limit store used by the {@code test}/{@code pgverify} profiles and any
 * deployment whose {@code netherite.distributed.provider} is {@code memory}.
 *
 * <p>The store guarantees the same semantics as the Redis provider within one process:
 * increments are atomic per key, an over-limit request rolls its increments back so it never
 * consumes quota, and expired windows reset lazily on access. A probabilistic sweep keeps the
 * map bounded even when this process never touches a key again.</p>
 */
@Component
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider", havingValue = "memory")
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Beyond this many live counters a sweep is triggered to evict expired windows. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public RateLimitDecision checkAndIncrement(List<BucketKey> buckets) {
        if (buckets.isEmpty()) {
            return RateLimitDecision.allow();
        }

        long nowMs = System.currentTimeMillis();
        List<String> incremented = new ArrayList<>(buckets.size());
        int soonestRetryMs = Integer.MAX_VALUE;
        boolean denied = false;

        for (BucketKey bucket : buckets) {
            long windowMs = bucket.windowSeconds() * 1000L;
            WindowCounter counter = counters.computeIfAbsent(bucket.key(), k -> new WindowCounter());
            synchronized (counter) {
                if (nowMs - counter.windowStartMillis >= windowMs) {
                    counter.reset(nowMs);
                }
                if (counter.count == 0) {
                    // Anchor the fixed window at the first hit, mirroring PEXPIRE-on-INCR==1
                    // in the Redis provider; subsequent hits never re-anchor the window.
                    counter.windowStartMillis = nowMs;
                }
                counter.count++;
                incremented.add(bucket.key());
                if (counter.count > bucket.limit()) {
                    denied = true;
                    long elapsed = Math.max(0, nowMs - counter.windowStartMillis);
                    int remainingMs = (int) Math.max(1, windowMs - elapsed);
                    soonestRetryMs = Math.min(soonestRetryMs, remainingMs);
                }
            }
        }

        if (denied) {
            for (String key : incremented) {
                WindowCounter counter = counters.get(key);
                if (counter != null) {
                    synchronized (counter) {
                        if (counter.count > 0) {
                            counter.count--;
                        }
                    }
                }
            }
            int retryAfter = Math.max(1, (soonestRetryMs + 999) / 1000);
            return RateLimitDecision.deny(retryAfter);
        }

        maybeSweep();
        return RateLimitDecision.allow();
    }

    private void maybeSweep() {
        if (counters.size() < SWEEP_THRESHOLD) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        counters.entrySet().removeIf(entry -> {
            WindowCounter counter = entry.getValue();
            synchronized (counter) {
                // A counter whose window started long ago but was never surface-updated is stale;
                // 24h is the upper bound so sweeping never evicts an active fixed window longer
                // than the longest configured window by a wide margin.
                return nowMs - counter.windowStartMillis > 24L * 60 * 60 * 1000;
            }
        });
    }

    private static final class WindowCounter {
        private long windowStartMillis;
        private long count;

        void reset(long nowMs) {
            this.windowStartMillis = nowMs;
            this.count = 0;
        }

        void decrement() {
            this.count--;
        }
    }
}