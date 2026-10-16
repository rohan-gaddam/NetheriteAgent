package io.netherite.observability;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS8 — per-key emission throttle (anti log-storm control).
 *
 * <p>The very first emission for a key must be allowed (a throttle must never swallow the
 * first signal of an outage); subsequent emissions are gated by the interval, and keys do
 * not interfere with each other.</p>
 */
class ThrottledRateTest {

    @Test
    void firstEmissionIsAllowedThenBlockedUntilIntervalElapses() {
        AtomicLong clock = new AtomicLong(0);
        ThrottledRate rate = new ThrottledRate(10_000_000, clock::get);

        assertThat(rate.tryEmitOncePer("key")).isTrue();
        assertThat(rate.tryEmitOncePer("key")).isFalse();

        clock.addAndGet(5_000_000);
        assertThat(rate.tryEmitOncePer("key")).isFalse();

        clock.addAndGet(5_000_000);
        assertThat(rate.tryEmitOncePer("key")).isTrue();
        assertThat(rate.tryEmitOncePer("key")).isFalse();
    }

    @Test
    void keysAreIndependent() {
        AtomicLong clock = new AtomicLong(100);
        ThrottledRate rate = new ThrottledRate(10_000_000, clock::get);

        assertThat(rate.tryEmitOncePer("a")).isTrue();
        assertThat(rate.tryEmitOncePer("b")).isTrue();
        assertThat(rate.tryEmitOncePer("a")).isFalse();
        assertThat(rate.tryEmitOncePer("b")).isFalse();
    }

    @Test
    void globalThirtySecondInstanceAllowsFirstAndThrottlesRepeat() {
        String key = "test:global:" + UUID.randomUUID();
        assertThat(ThrottledRate.GLOBAL_30S.tryEmitOncePer(key)).isTrue();
        assertThat(ThrottledRate.GLOBAL_30S.tryEmitOncePer(key)).isFalse();
    }
}