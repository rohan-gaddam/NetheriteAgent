package io.netherite.distributed;

import io.netherite.state.service.JdbcIdempotencyStore;
import io.netherite.state.spi.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WORKSTREAM 3 - Durable, cluster-wide idempotency against REAL PostgreSQL. Two
 * {@link JdbcIdempotencyStore} instances talk to the same database - the stored
 * procedure analogue of two replicas sharing one authority. Proves claim once /
 * concurrent single-winner / crash takeover / fail-then-retry / tenant isolation /
 * completed-replay retention all survive process restarts (they are plain rows).
 */
@SpringBootTest
@ActiveProfiles("distributed")
class DistributedIdempotencyIT {

    @Autowired
    private JdbcIdempotencyStore storeA;

    @Autowired
    private DataSource dataSource;

    private IdempotencyStore storeB; // second replica sharing the same DB

    @BeforeEach
    void secondReplica() {
        storeB = new JdbcIdempotencyStore(new JdbcTemplate(dataSource));
    }

    private String org() {
        return UUID.randomUUID().toString();
    }

    private String key() {
        return "it-key-" + UUID.randomUUID();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private int attemptCount(String organizationId, String idempotencyKey) {
        Integer count = jdbc().queryForObject(
                "SELECT attempt_count FROM idempotency_records WHERE organization_id = CAST(? AS uuid) AND idempotency_key = ?",
                Integer.class, organizationId, idempotencyKey);
        return count == null ? -1 : count;
    }

    @Test
    @DisplayName("A key is claimed exactly once and denied afterwards from any replica")
    void claimOnceThenDenied() {
        String o = org();
        String k = key();
        assertTrue(storeA.tryClaim(o, k, Duration.ofMinutes(10)));
        assertFalse(storeB.tryClaim(o, k, Duration.ofMinutes(10)), "Second replica must be denied");
        assertFalse(storeA.tryClaim(o, k, Duration.ofMinutes(10)), "Same replica re-claim must be denied");
    }

    @Test
    @DisplayName("Concurrent races against the primary key produce exactly one winner")
    void concurrentClaimsSingleWinner() throws Exception {
        String o = org();
        String k = key();
        int threads = 30;
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await(20, TimeUnit.SECONDS);
                        if (storeA.tryClaim(o, k, Duration.ofMinutes(10))) {
                            winners.incrementAndGet();
                        }
                        return null;
                    }))
                    .collect(Collectors.toList());
            for (var f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, winners.get(), "Exactly one concurrent claimant must win");
    }

    @Test
    @DisplayName("PROCESSING state is visible across replicas and completes with a replayable result")
    void survivesAcrossReplicas() {
        String o = org();
        String k = key();
        assertTrue(storeA.tryClaim(o, k, Duration.ofMinutes(10), "exec-1"));

        var observedByB = storeB.getRecord(o, k).orElseThrow();
        assertEquals(IdempotencyStore.IdempotencyState.PROCESSING, observedByB.state());

        storeB.markCompleted(o, k, "{\"status\":\"EXECUTED\"}", Duration.ofMinutes(10));

        var replayedByA = storeA.getRecord(o, k).orElseThrow();
        assertEquals(IdempotencyStore.IdempotencyState.COMPLETED, replayedByA.state());
        assertEquals("{\"status\":\"EXECUTED\"}", replayedByA.cachedResponseJson());
    }

    @Test
    @DisplayName("A crashed processor's expired claim is taken over by another replica with attempt bump")
    void crashedProcessorTakenOverAfterTtl() throws InterruptedException {
        String o = org();
        String k = key();
        assertTrue(storeA.tryClaim(o, k, Duration.ofSeconds(1)));
        assertEquals(1, attemptCount(o, k));

        Thread.sleep(1600); // claim TTL elapses as if the executing node crashed

        assertTrue(storeB.tryClaim(o, k, Duration.ofMinutes(10)));
        assertEquals(2, attemptCount(o, k), "Takeover of a PROCESSING claim must bump the attempt count");
        assertEquals(IdempotencyStore.IdempotencyState.PROCESSING, storeB.getRecord(o, k).orElseThrow().state());
    }

    @Test
    @DisplayName("Deterministic failure short-circuits re-claim, then a fresh execution becomes possible")
    void failedMarkBlocksReplayThenAllowsReExecution() throws InterruptedException {
        String o = org();
        String k = key();
        assertTrue(storeA.tryClaim(o, k, Duration.ofMinutes(10)));

        storeB.markFailed(o, k, Duration.ofSeconds(2));

        assertFalse(storeA.tryClaim(o, k, Duration.ofMinutes(10)), "FAILED record must block immediate re-claim");
        assertEquals(IdempotencyStore.IdempotencyState.FAILED, storeA.getRecord(o, k).orElseThrow().state());

        Thread.sleep(2600); // failure window elapses

        assertTrue(storeA.tryClaim(o, k, Duration.ofMinutes(10)), "After the failure window a new attempt may start");
        assertEquals(1, attemptCount(o, k), "A FAILED->PROCESSING takeover restarts the attempt counter");
    }

    @Test
    @DisplayName("Tenant isolation: identical keys in different organizations never collide")
    void tenantIsolation() {
        String k = key();
        String oA = org();
        String oB = org();
        assertTrue(storeA.tryClaim(oA, k, Duration.ofMinutes(10)));
        assertTrue(storeB.tryClaim(oB, k, Duration.ofMinutes(10)), "Same key under a different org must claim independently");
        assertTrue(storeA.getRecord(oA, k).isPresent());
        assertFalse(storeA.tryClaim(oA, k, Duration.ofMinutes(10)), "Same org may not re-claim its active key");
        assertTrue(storeA.getRecord(oB, k).isPresent(), "Each org's identical key lives as an independent record");
    }

    @Test
    @DisplayName("Completed records remain replayable within the retention window and are reusable after it")
    void completedReplayWithinRetentionThenReusable() throws InterruptedException {
        String o = org();
        String k = key();
        assertTrue(storeA.tryClaim(o, k, Duration.ofSeconds(1)));
        storeA.markCompleted(o, k, "{\"ok\":true}", Duration.ofSeconds(1));

        var withinWindow = storeA.getRecord(o, k).orElseThrow();
        assertEquals(IdempotencyStore.IdempotencyState.COMPLETED, withinWindow.state());

        Thread.sleep(1600); // retention elapses

        assertFalse(storeA.getRecord(o, k).isPresent(), "Expired record is no longer visible");
        assertTrue(storeA.tryClaim(o, k, Duration.ofMinutes(10)), "Expired finished record may be reused for a new execution");
    }
}