package io.netherite.state.service;

import io.netherite.state.model.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

/**
 * Redisson-backed lock handle. {@link #unlock()} verifies current-thread ownership before
 * releasing so a stale/failed holder can never release a lock now owned by a newer holder.
 */
@Slf4j
@RequiredArgsConstructor
public class RedissonDistributedLock implements DistributedLock {

    private final RLock lock;
    private final String lockKey;
    private volatile boolean held = true;

    @Override
    public String getLockKey() {
        return lockKey;
    }

    @Override
    public boolean isHeld() {
        return held && lock.isHeldByCurrentThread();
    }

    @Override
    public void unlock() {
        if (!held) {
            return;
        }
        held = false;
        if (lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                log.debug("Released lock {}", lockKey);
            } catch (RuntimeException e) {
                log.warn("Failed to release lock {}: {}", lockKey, e.getMessage());
            }
        } else {
            log.warn("Lock {} no longer held by this thread (expired or re-acquired elsewhere) - not releasing",
                    lockKey);
        }
    }
}