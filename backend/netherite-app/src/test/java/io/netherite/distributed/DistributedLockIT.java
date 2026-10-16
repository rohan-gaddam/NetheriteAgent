package io.netherite.distributed;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.service.RedisDistributedLockManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.Redisson;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WORKSTREAM 3 - Distributed locking against REAL Redis (two independent Redisson
 * clients = two cluster nodes), including lease expiry (crashed-holder recovery),
 * ownership-safe release, fail-closed behaviour while Redis is unreachable, and
 * automatic recovery after a live Redis outage and restart.
 */
@SpringBootTest
@ActiveProfiles("distributed")
class DistributedLockIT {

    /**
     * Portable Redis test-tooling resolution (public-release safe: no machine-specific
     * absolute paths). Override with {@code -Dtest.redis.home=<dir>} or
     * {@code REDIS_HOME=<dir>} (directory containing {@code redis-cli.exe}); otherwise
     * {@code redis-cli} is resolved from {@code PATH}. The restart script used by the
     * live-outage test is {@code -Dtest.redis.bootBat=<file>} or
     * {@code REDIS_BOOT_BAT=<file>}; it must be set explicitly for that test.
     */
    private static final String REDIS_HOME = resolveRedisHome();
    private static final String BOOT_BAT = resolveBootBat();

    private static String resolveRedisHome() {
        String home = System.getProperty("test.redis.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("REDIS_HOME");
        }
        return (home == null || home.isBlank()) ? "" : home;
    }

    private static String resolveBootBat() {
        String boot = System.getProperty("test.redis.bootBat");
        if (boot == null || boot.isBlank()) {
            boot = System.getenv("REDIS_BOOT_BAT");
        }
        return (boot == null || boot.isBlank()) ? "" : boot;
    }

    @Autowired
    private RedisDistributedLockManager managerA;

    @Autowired
    private NetheriteDistributedProperties properties;

    private final List<RedissonClient> clientsToShutdown = new ArrayList<>();

    private RedisDistributedLockManager secondNodeOnSameRedis() {
        return managerFor(redisson("localhost", 6379));
    }

    private RedisDistributedLockManager unreachableRedis() {
        return managerFor(redisson("localhost", 6399));
    }

    private RedissonClient redisson(String host, int port) {
        Config cfg = new Config();
        cfg.setCodec(new StringCodec());
        cfg.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(1500)
                .setTimeout(1500)
                .setRetryAttempts(1)
                .setRetryInterval(200)
                .setConnectionPoolSize(2)
                .setConnectionMinimumIdleSize(0);
        RedissonClient client = Redisson.create(cfg);
        clientsToShutdown.add(client);
        return client;
    }

    private RedisDistributedLockManager managerFor(RedissonClient client) {
        return new RedisDistributedLockManager(client, properties);
    }

    @AfterEach
    void shutdownOwnedClients() {
        List<RedissonClient> clients = new ArrayList<>(clientsToShutdown);
        clientsToShutdown.clear();
        clients.forEach(c -> {
            try {
                c.shutdown(0, 2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort - never let a client teardown hang a test JVM
            }
        });
    }

    private String key(String prefix) {
        return prefix + ":" + UUID.randomUUID();
    }

    @Test
    @DisplayName("Two independent nodes on the same Redis mutually exclude one another")
    void twoNodesMutuallyExclude() {
        RedisDistributedLockManager nodeB = secondNodeOnSameRedis();
        String k = key("it:excl");

        Optional<DistributedLock> ownedByA = managerA.tryAcquireLock(k, Duration.ZERO, Duration.ofSeconds(30));
        assertTrue(ownedByA.isPresent());
        assertTrue(ownedByA.get().isHeld());

        assertTrue(managerBUnavailable(nodeB, k), "Node B must not acquire a lock held by Node A");

        ownedByA.get().unlock();
        assertTrue(waitUntilAcquired(nodeB, k, Duration.ofSeconds(5)),
                "Node B must acquire the lock after Node A releases it");
    }

    @Test
    @DisplayName("Watchdog (no explicit lease) lock blocks a second node until explicitly released")
    void watchdogLockExcludesSecondNode() {
        RedisDistributedLockManager nodeB = secondNodeOnSameRedis();
        String k = key("it:watchdog");

        Optional<DistributedLock> ownedByA = managerA.tryAcquireLock(k, Duration.ZERO);
        assertTrue(ownedByA.isPresent());

        assertTrue(managerBUnavailable(nodeB, k), "Watchdog lock must exclude Node B while held");

        ownedByA.get().unlock();
        assertTrue(waitUntilAcquired(nodeB, k, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("Lease expiry recovers a crashed holder: after TTL elapses another node acquires")
    void leaseExpiryRecoversCrashedHolder() throws InterruptedException {
        assertTrue(redisUp(), "Precondition: Redis must be running on 6379");
        RedisDistributedLockManager nodeB = secondNodeOnSameRedis();
        String k = key("it:crash");

        Optional<DistributedLock> heldByA = managerA.tryAcquireLock(k, Duration.ZERO, Duration.ofSeconds(2));
        assertTrue(heldByA.isPresent());
        assertTrue(managerBUnavailable(nodeB, k), "Held lock must be invisible to Node B during the lease");

        Thread.sleep(2500); // lease elapses as if Node A crashed without unlocking

        assertTrue(waitUntilAcquired(nodeB, k, Duration.ofSeconds(6)),
                "Node B must acquire the lock once the crashed holder's lease expires");
    }

    @Test
    @DisplayName("releaseLock is ownership-safe: a non-owner can never free another holder's lock")
    void ownershipSafeRelease() {
        RedisDistributedLockManager nodeB = secondNodeOnSameRedis();
        String k = key("it:owner");

        Optional<DistributedLock> heldByA = managerA.tryAcquireLock(k, Duration.ZERO, Duration.ofSeconds(20));
        assertTrue(heldByA.isPresent());

        nodeB.releaseLock(k); // Node B must NOT release Node A's lock
        assertTrue(managerBUnavailable(nodeB, k), "Non-owner release must not free the lock");

        heldByA.get().unlock();
        assertTrue(waitUntilAcquired(nodeB, k, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("Fail-closed: Redis unreachable surfaces ERR_SYSTEM_UNAVAILABLE, never a silent fallback")
    void failClosedWhenRedisUnreachable() {
        RedissonClient deadClient = redisson("localhost", 6379);
        deadClient.shutdown();
        RedisDistributedLockManager dead = managerFor(deadClient);

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> dead.tryAcquireLock(key("it:dead"), Duration.ZERO, Duration.ofSeconds(5)));
        assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, ex.getErrorCode());
        assertTrue(managerA.tryAcquireLock(key("it:after"), Duration.ZERO, Duration.ofSeconds(5)).isPresent(),
                "The healthy manager on live Redis must keep working while the dead client fails closed");
    }

    @Test
    @DisplayName("Live Redis outage: lock operations fail closed, then recover automatically after restart")
    void realOutageBlocksThenRecovers() throws Exception {
        assertTrue(redisUp(), "Precondition: Redis must be running on 6379");
        try {
            redisCli(List.of("-p", "6379", "shutdown"));
            awaitRedisDown(Duration.ofSeconds(10));

            NetheriteException ex = assertThrows(NetheriteException.class,
                    () -> managerA.tryAcquireLock(key("it:outage"), Duration.ZERO, Duration.ofSeconds(5)));
            assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, ex.getErrorCode(),
                    "While Redis is down the control plane must fail closed");
        } finally {
            restartRedis();
            assertTrue(awaitRedisUp(Duration.ofSeconds(20)), "Redis must be restored before the test ends");
        }

        Optional<DistributedLock> recovered = Optional.empty();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && recovered.isEmpty()) {
            try {
                recovered = managerA.tryAcquireLock(key("it:recovered"), Duration.ZERO, Duration.ofSeconds(10));
            } catch (NetheriteException reconnectPending) {
                recovered = Optional.empty(); // pooled client still reconnecting - keep retrying
            }
            if (recovered.isEmpty()) {
                Thread.sleep(250);
            }
        }
        assertTrue(recovered.isPresent(), "Lock manager must transparently recover once Redis is back");
        recovered.get().unlock();
    }

    private boolean managerBUnavailable(RedisDistributedLockManager nodeB, String k) {
        return nodeB.tryAcquireLock(k, Duration.ofMillis(400), Duration.ofSeconds(10)).isEmpty();
    }

    private boolean waitUntilAcquired(RedisDistributedLockManager nodeB, String k, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        boolean acquired = false;
        Optional<DistributedLock> lock = Optional.empty();
        while (System.nanoTime() < deadline && !acquired) {
            lock = nodeB.tryAcquireLock(k, Duration.ZERO, Duration.ofSeconds(10));
            acquired = lock.isPresent();
            if (!acquired) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        lock.ifPresent(DistributedLock::unlock);
        return acquired;
    }

    // -- Redis process control helpers ---------------------------------------------

    private boolean redisUp() {
        return "PONG".equalsIgnoreCase(redisCli(List.of("ping")));
    }

    private void awaitRedisDown(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!redisUp()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("Redis did not go down within " + timeout);
    }

    private void restartRedis() {
        if (BOOT_BAT.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot restart Redis: set -Dtest.redis.bootBat=<script> or REDIS_BOOT_BAT=<script>");
        }
        try {
            // Discard all output handles: the spawned redis-server outlives the test JVM,
            // and an inherited stdout/stderr pipe would otherwise keep the parent shell
            // (maven/CI) waiting forever after the fork exits.
            new ProcessBuilder("cmd.exe", "/c", BOOT_BAT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to restart Redis", e);
        }
    }

    private boolean awaitRedisUp(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (redisUp()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private String redisCli(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(REDIS_HOME.isEmpty() ? "redis-cli" : REDIS_HOME + "\\redis-cli.exe");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new IllegalStateException("redis-cli invocation failed", e);
        }
    }
}