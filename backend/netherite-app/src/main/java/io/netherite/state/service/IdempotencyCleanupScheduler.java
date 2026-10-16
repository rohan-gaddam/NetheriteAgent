package io.netherite.state.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded sweep that deletes fully-expired idempotency records (retention reached).
 * Safe to run on every replica - deletes are idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
        havingValue = "redis", matchIfMissing = true)
public class IdempotencyCleanupScheduler {

    private final JdbcIdempotencyStore idempotencyStore;

    @Scheduled(fixedDelayString = "${netherite.distributed.idempotency.cleanup-interval-ms:1800000}",
            initialDelayString = "300000")
    public void purgeExpiredIdempotencyRecords() {
        try {
            int removed = idempotencyStore.purgeExpired(5000);
            if (removed > 0) {
                log.debug("Idempotency cleanup removed {} expired records", removed);
            }
        } catch (Exception e) {
            log.warn("Idempotency cleanup sweep failed: {}", e.getMessage());
        }
    }
}