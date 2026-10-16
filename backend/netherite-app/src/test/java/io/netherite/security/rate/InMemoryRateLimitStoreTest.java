package io.netherite.security.rate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRateLimitStoreTest {

    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore();

    private static RateLimitStore.BucketKey key(String k, int limit, long windowSeconds) {
        return new RateLimitStore.BucketKey(k, limit, windowSeconds);
    }

    @Test
    @DisplayName("Non-empty buckets allow while under the limit and deny once over")
    void allowsUpToLimitThenDenies() {
        List<RateLimitStore.BucketKey> buckets = List.of(key("rl:test", 3, 60));
        for (int i = 0; i < 3; i++) {
            assertTrue(store.checkAndIncrement(buckets).allowed(), "request " + (i + 1) + " should be allowed");
        }
        RateLimitDecision denied = store.checkAndIncrement(buckets);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1);
    }

    @Test
    @DisplayName("A denied request rolls back every bucket so a sibling bucket is not drained")
    void deniedRequestDoesNotDrainSiblingBucket() {
        // ip bucket limit 3, per-user bucket limit 2. A flood from user A runs up the
        // user bucket; the rolled-back ip counter must still admit a different user.
        List<RateLimitStore.BucketKey> userABuckets = List.of(
                key("rl:ip", 3, 60),
                key("rl:userA", 2, 60));
        List<RateLimitStore.BucketKey> userBBuckets = List.of(
                key("rl:ip", 3, 60),
                key("rl:userB", 2, 60));

        assertTrue(store.checkAndIncrement(userABuckets).allowed());
        assertTrue(store.checkAndIncrement(userABuckets).allowed());
        assertFalse(store.checkAndIncrement(userABuckets).allowed());
        assertFalse(store.checkAndIncrement(userABuckets).allowed());

        // Without rollback the shared ip bucket would have been inflated to 4+ and blocked B.
        assertTrue(store.checkAndIncrement(userBBuckets).allowed(),
                "denied requests must not consume the shared ip bucket quota");
    }

    @Test
    @DisplayName("Window expiry resets the counter so traffic may proceed again")
    void windowExpiryResetsCounter() throws InterruptedException {
        List<RateLimitStore.BucketKey> buckets = List.of(key("rl:window", 1, 1));
        assertTrue(store.checkAndIncrement(buckets).allowed());
        assertFalse(store.checkAndIncrement(buckets).allowed());
        Thread.sleep(1100);
        assertTrue(store.checkAndIncrement(buckets).allowed());
    }

    @Test
    @DisplayName("Empty bucket lists always allow")
    void emptyBucketsAllow() {
        assertTrue(store.checkAndIncrement(List.of()).allowed());
    }

    @Test
    @DisplayName("Window is anchored at the first hit and not re-anchored by later hits")
    void windowNotReAnchoredByActivity() throws InterruptedException {
        List<RateLimitStore.BucketKey> buckets = List.of(key("rl:anchor", 2, 1));
        assertTrue(store.checkAndIncrement(buckets).allowed());
        assertTrue(store.checkAndIncrement(buckets).allowed());
        assertFalse(store.checkAndIncrement(buckets).allowed());
        Thread.sleep(1100);
        assertTrue(store.checkAndIncrement(buckets).allowed());
    }

    @Test
    @DisplayName("Concurrent contenders never exceed the bucket limit")
    void concurrentContendersNeverExceedLimit() throws InterruptedException {
        int contenders = 20;
        int limit = 5;
        List<RateLimitStore.BucketKey> buckets = List.of(key("rl:concurrent", limit, 60));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (store.checkAndIncrement(buckets).allowed()) {
                        admitted.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, admitted.get());
    }
}