package io.netherite.state.spi;

import java.time.Duration;
import java.util.Optional;

/**
 * SPI for managing action idempotency keys to prevent duplicate execution.
 */
public interface IdempotencyStore {

    enum IdempotencyState {
        PROCESSING,
        COMPLETED,
        FAILED
    }

    record IdempotencyRecord(String key, IdempotencyState state, String cachedResponseJson) {}

    /**
     * Atomically claims an idempotency key if not present. Returns true if acquired.
     */
    boolean tryClaim(String organizationId, String idempotencyKey, Duration ttl);

    /**
     * Atomically claims an idempotency key, recording the correlating execution id (for
     * observability). Returns true if acquired.
     */
    default boolean tryClaim(String organizationId, String idempotencyKey, Duration ttl, String executionId) {
        return tryClaim(organizationId, idempotencyKey, ttl);
    }

    /**
     * Retrieves an existing idempotency record.
     */
    Optional<IdempotencyRecord> getRecord(String organizationId, String idempotencyKey);

    /**
     * Marks the idempotency key as completed with cached result.
     */
    void markCompleted(String organizationId, String idempotencyKey, String responseJson, Duration ttl);

    /**
     * Marks the idempotency key as failed (deterministic, no external side effect for the
     * majority of cases). A FAILED record short-circuits immediate re-claims until its TTL
     * expires, surfacing the failure instead of hanging in PROCESSING for the execution TTL.
     */
    void markFailed(String organizationId, String idempotencyKey, Duration ttl);
}
