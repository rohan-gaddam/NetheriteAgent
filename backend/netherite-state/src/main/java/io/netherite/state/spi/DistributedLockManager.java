package io.netherite.state.spi;

import io.netherite.state.model.DistributedLock;

import java.time.Duration;
import java.util.Optional;

/**
 * SPI for distributed lock management across cluster nodes.
 */
public interface DistributedLockManager {

    /**
     * Attempts to acquire a distributed lock on the specified entity.
     */
    Optional<DistributedLock> tryAcquireLock(String lockKey, Duration waitTime, Duration leaseTime);

    /**
     * Acquires a distributed lock with an auto-renewing (watchdog) lease, suited for
     * long-running scheduler cycles whose duration is not known in advance. The lease is
     * renewed while the JVM is alive and the lock is held, and expires promptly after a
     * crash. Implementations that cannot renew may fall back to a generous fixed lease.
     *
     * @param lockKey  the entity key to lock
     * @param waitTime bounded wait time before the attempt is abandoned
     * @return the lock handle if acquired, empty otherwise
     */
    default Optional<DistributedLock> tryAcquireLock(String lockKey, Duration waitTime) {
        return tryAcquireLock(lockKey, waitTime, Duration.ofMinutes(10));
    }

    /**
     * Releases a lock identified by key.
     */
    void releaseLock(String lockKey);
}
