package io.netherite.state.service;

import io.netherite.state.model.DistributedLock;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple in-memory distributed lock implementation.
 */
@Slf4j
public class SimpleDistributedLock implements DistributedLock {

    private final String lockKey;
    private final String owner;
    private final InMemoryDistributedLockManager lockManager;
    private volatile boolean held;

    public SimpleDistributedLock(String lockKey, String owner, InMemoryDistributedLockManager lockManager) {
        this.lockKey = lockKey;
        this.owner = owner;
        this.lockManager = lockManager;
        this.held = true;
    }

    @Override
    public String getLockKey() {
        return lockKey;
    }

    @Override
    public boolean isHeld() {
        return held;
    }

    @Override
    public void unlock() {
        if (held) {
            held = false;
            if (!lockManager.releaseLockIfOwner(lockKey, owner)) {
                log.warn("Lock {} no longer held by owner {} - not releasing", lockKey, owner);
            } else {
                log.debug("Released lock: {}", lockKey);
            }
        }
    }
}
