package io.netherite.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-key emission throttle for high-volume log sites (WS8 anti log-storm control).
 *
 * <p>Endpoints that can fire many events per second when a dependency is down — OPA
 * unreachable, Redis outage, rate-limit denials — must not turn an outage into a log
 * storm. {@link #tryEmitOncePer(String, long)} allows the caller to emit at most once
 * per {@code intervalNanos} per key per JVM. The decision is purely a first-touch token
 * bucket (no per-key state beyond the last emission timestamp).</p>
 */
public class ThrottledRate {

    private static final LongSupplier SYSTEM_CLOCK = System::nanoTime;

    /** Global 30-second throttle, convenient for the common "once per 30s" case. */
    public static final ThrottledRate GLOBAL_30S = new ThrottledRate(30_000_000_000L);

    private final long intervalNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, AtomicLong> lastEmissionAt = new ConcurrentHashMap<>();

    public ThrottledRate(long intervalNanos) {
        this(intervalNanos, SYSTEM_CLOCK);
    }

    /**
     * Test-friendly constructor with a controllable time source.
     */
    public ThrottledRate(long intervalNanos, LongSupplier clock) {
        this.intervalNanos = intervalNanos;
        this.clock = clock;
    }

    /**
     * @return {@code true} if the caller may emit for {@code key} now (first emission or
     *         interval elapsed since the last one); {@code false} to stay silent.
     */
    public boolean tryEmitOncePer(String key) {
        long now = clock.getAsLong();
        // Pre-seed the bucket so the very first emission for a key is allowed.
        AtomicLong last = lastEmissionAt.computeIfAbsent(key, k -> new AtomicLong(now - intervalNanos));
        long previous = last.get();
        if (now - previous < intervalNanos) {
            return false;
        }
        return last.compareAndSet(previous, now);
    }
}