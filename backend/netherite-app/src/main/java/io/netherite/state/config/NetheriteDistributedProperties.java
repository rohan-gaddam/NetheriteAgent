package io.netherite.state.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the distributed coordination layer (locks, idempotency, recovery).
 *
 * <p>{@code netherite.distributed.provider} selects the lock-provider implementation:
 * {@code redis} (default, Redisson-backed, fail-closed) or {@code memory} (single-node,
 * used by the {@code test} and {@code pgverify} profiles).
 *
 * <p>The idempotency store is always the durable PostgreSQL-backed implementation when the
 * provider is {@code redis}; {@code memory} swaps in the in-memory store for tests.
 */
@Data
@Component
@ConfigurationProperties(prefix = "netherite.distributed")
public class NetheriteDistributedProperties {

    /** Lock/coordination provider: {@code redis} (default) or {@code memory}. */
    private String provider = "redis";

    /** Namespace prefix applied to every Redis lock key. */
    private String lockPrefix = "netherite:lock:";

    private Node node = new Node();
    private Lock lock = new Lock();
    private Idempotency idempotency = new Idempotency();
    private Redis redis = new Redis();

    @Data
    public static class Redis {
        private int connectTimeoutMillis = 3000;
        private int timeoutMillis = 3000;
        private int connectionPoolSize = 16;
        private int connectionMinimumIdleSize = 4;
    }

    @Data
    public static class Node {
        /** Stable per-process identifier used in approval execution claims and logs. Auto-derived if blank. */
        private String id = "";
    }

    @Data
    public static class Lock {
        /** Bounded wait for an execution lock before abandoning. */
        private long executionWaitMillis = 5000;
        /** Fixed lease for an execution lock (bounded work = connector call). */
        private long executionLeaseMillis = 30000;
        /** Wait for scheduler cycle locks (non-blocking). */
        private long schedulerWaitMillis = 0;
    }

    @Data
    public static class Idempotency {
        /** PROCESSING claim freshness before an expired claim may be taken over (crash window). */
        private long executionTtlMinutes = 30;
        /** Retention for COMPLETED records (idempotent replay window). */
        private long completedTtlMinutes = 30;
        /** Short window during which a FAILED record is surfaced instead of re-executed. */
        private long failedTtlSeconds = 120;
        /** Interval of the expired-record cleanup sweep. */
        private long cleanupIntervalSeconds = 1800;
        /** Lease applied to approval continuation claims. */
        private long approvalLeaseMinutes = 15;
        /** Maximum execution attempts per approval before it is marked terminal. */
        private int approvalMaxAttempts = 3;
        /** Interval of the stale-claim recovery sweep. */
        private long approvalRecoveryIntervalSeconds = 60;
    }

    public boolean isRedisProvider() {
        return "redis".equalsIgnoreCase(provider);
    }

    public boolean isMemoryProvider() {
        return "memory".equalsIgnoreCase(provider);
    }

    public String fullLockKey(String lockKey) {
        return (lockPrefix.endsWith(":") || lockKey.startsWith(lockPrefix)) ? lockPrefix + lockKey : lockPrefix + ":" + lockKey;
    }
}