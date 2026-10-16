package io.netherite.state.service;

import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory distributed lock manager for single-node deployments and unit tests only
 * (provider {@code memory}). Never used in multi-node runtime: elements are keyed by
 * thread identity and provide no cross-process mutual exclusion.
 */
@Component
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider", havingValue = "memory")
public class InMemoryDistributedLockManager implements DistributedLockManager {

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    private static class LockEntry {
        final String owner;
        final Instant expiresAt;

        LockEntry(String owner, Instant expiresAt) {
            this.owner = owner;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    @Override
    public synchronized Optional<DistributedLock> tryAcquireLock(String lockKey, Duration waitTime, Duration leaseTime) {
        LockEntry existing = locks.get(lockKey);
        if (existing != null && !existing.isExpired()) {
            return Optional.empty();
        }

        String owner = Thread.currentThread().getId() + ":" + System.nanoTime();
        locks.put(lockKey, new LockEntry(owner, Instant.now().plus(leaseTime)));

        return Optional.of(new SimpleDistributedLock(lockKey, owner, this));
    }

    @Override
    public synchronized void releaseLock(String lockKey) {
        locks.remove(lockKey);
    }

    synchronized boolean releaseLockIfOwner(String lockKey, String owner) {
        LockEntry entry = locks.get(lockKey);
        if (entry != null && owner != null && owner.equals(entry.owner)) {
            locks.remove(lockKey);
            return true;
        }
        return false;
    }

    synchronized boolean isLockHeld(String lockKey) {
        LockEntry entry = locks.get(lockKey);
        return entry != null && !entry.isExpired();
    }
}
