package io.netherite.state.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redisson-backed distributed lock manager (RLock: SET NX + Lua, ownership token, lease
 * expiry, optional watchdog renewal). Cross-process safe when multiple replicas share one
 * Redis.
 *
 * <p>Fail-closed: when Redis is unreachable every coordination call surfaces
 * {@code ERR_SYSTEM_UNAVAILABLE}; the control plane never silently falls back to an
 * in-memory lock in a multi-node deployment.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
        havingValue = "redis", matchIfMissing = true)
public class RedisDistributedLockManager implements DistributedLockManager {

    private static final long UNREACHABLE_LOG_THROTTLE_NANOS = 5_000_000_000L;

    private final RedissonClient redisson;
    private final NetheriteDistributedProperties properties;
    private final NetheriteMetrics metrics;

    private volatile long lastUnreachableLogNanos = 0;

    /** Preserves the direct-construction contract used by integration tests. */
    public RedisDistributedLockManager(
            @Lazy @Qualifier("redissonClient") RedissonClient redisson,
            NetheriteDistributedProperties properties) {
        this(redisson, properties, null);
    }

    @Autowired
    public RedisDistributedLockManager(
            @Lazy @Qualifier("redissonClient") RedissonClient redisson,
            NetheriteDistributedProperties properties,
            NetheriteMetrics metrics) {
        this.redisson = redisson;
        this.properties = properties;
        this.metrics = metrics != null ? metrics : NetheriteMetrics.NOOP;
    }

    @Override
    public Optional<DistributedLock> tryAcquireLock(String lockKey, Duration waitTime, Duration leaseTime) {
        String fullKey = properties.fullLockKey(lockKey);
        RLock lock = null;
        try {
            lock = redisson.getLock(fullKey);
            long start = System.nanoTime();
            boolean acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Lock acquire {} -> {} (wait {}ms, lease {}ms, {}ms)",
                    fullKey, acquired, waitTime.toMillis(), leaseTime.toMillis(),
                    (System.nanoTime() - start) / 1_000_000);
            if (!acquired) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            throw failClosed(e, lockKey);
        }
        return Optional.of(new RedissonDistributedLock(lock, fullKey));
    }

    @Override
    public Optional<DistributedLock> tryAcquireLock(String lockKey, Duration waitTime) {
        String fullKey = properties.fullLockKey(lockKey);
        RLock lock = null;
        try {
            lock = redisson.getLock(fullKey);
            long start = System.nanoTime();
            boolean acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Lock acquire(watchdog) {} -> {} (wait {}ms, {}ms)",
                    fullKey, acquired, waitTime.toMillis(), (System.nanoTime() - start) / 1_000_000);
            if (!acquired) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            throw failClosed(e, lockKey);
        }
        return Optional.of(new RedissonDistributedLock(lock, fullKey));
    }

    @Override
    public void releaseLock(String lockKey) {
        String fullKey = properties.fullLockKey(lockKey);
        try {
            RLock lock = redisson.getLock(fullKey);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released {}", fullKey);
            } else {
                log.warn("Skipping release of {} - either not held, already expired, or owned by another holder", fullKey);
            }
        } catch (RuntimeException e) {
            throw failClosed(e, lockKey);
        }
    }

    private NetheriteException failClosed(RuntimeException cause, String lockKey) {
        metrics.redisFailure("lock");
        long now = System.nanoTime();
        if (now - lastUnreachableLogNanos > UNREACHABLE_LOG_THROTTLE_NANOS) {
            lastUnreachableLogNanos = now;
            log.error("Redis coordination dependency unreachable during lock {}: {} — failing closed",
                    lockKey, cause.getMessage());
        }
        if (cause instanceof NetheriteException) {
            return (NetheriteException) cause;
        }
        return new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                "Distributed coordination dependency (Redis) unavailable");
    }
}