package io.netherite.security.rate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local circuit breaker around the distributed rate-limit dependency.
 *
 * <p>Once the Redis broker stops answering, opening the breaker stops further Redis round-trips
 * (bounded fail-out instead of one slow timeout per request) and lets {@link RateLimitService}
 * answer from the route's fail-closed/fail-open policy. After the open window elapses the breaker
 * half-opens and allows a probe; a successful probe resets the failure counter.</p>
 */
@Component
@RequiredArgsConstructor
public class RateLimitDependencyGuard {

    private final RateLimitProperties properties;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Long> openedAtMillis = new AtomicReference<>(null);

    /**
     * @return {@code true} when the breaker is open and Redis must not be called.
     */
    public boolean isOpen() {
        Long openedAt = openedAtMillis.get();
        if (openedAt == null) {
            return false;
        }
        long openWindow = properties.getDependency().getCircuitBreakOpenMillis();
        if (System.currentTimeMillis() - openedAt > openWindow) {
            // Half-open: clear the reference so the next request probes Redis once.
            if (openedAtMillis.compareAndSet(openedAt, null)) {
                consecutiveFailures.set(0);
            }
            return false;
        }
        return true;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getDependency().getCircuitBreakFailures()) {
            openedAtMillis.set(System.currentTimeMillis());
        }
    }
}