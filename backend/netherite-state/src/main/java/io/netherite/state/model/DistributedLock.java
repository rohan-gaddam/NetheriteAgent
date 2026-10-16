package io.netherite.state.model;

import java.io.Closeable;

/**
 * Autocloseable handle representing an acquired distributed lock.
 */
public interface DistributedLock extends Closeable {

    String getLockKey();

    boolean isHeld();

    void unlock();

    @Override
    default void close() {
        unlock();
    }
}
