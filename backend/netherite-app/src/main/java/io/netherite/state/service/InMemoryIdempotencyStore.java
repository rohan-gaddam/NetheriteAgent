package io.netherite.state.service;

import io.netherite.state.spi.IdempotencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency store for single-node deployments and unit tests only (provider
 * {@code memory}). Not durable - a process restart clears all claims and completed keys.
 */
@Component
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider", havingValue = "memory")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyEntry> store = new ConcurrentHashMap<>();

    private static class IdempotencyEntry {
        final IdempotencyState state;
        final String cachedResponseJson;
        final Instant expiresAt;

        IdempotencyEntry(IdempotencyState state, String cachedResponseJson, Instant expiresAt) {
            this.state = state;
            this.cachedResponseJson = cachedResponseJson;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    @Override
    public synchronized boolean tryClaim(String organizationId, String idempotencyKey, Duration ttl) {
        String compositeKey = organizationId + ":" + idempotencyKey;
        IdempotencyEntry existing = store.get(compositeKey);

        if (existing != null && !existing.isExpired()) {
            if (existing.state == IdempotencyState.COMPLETED || existing.state == IdempotencyState.FAILED) {
                return false;
            }
            if (existing.state == IdempotencyState.PROCESSING) {
                return false;
            }
        }

        store.put(compositeKey, new IdempotencyEntry(IdempotencyState.PROCESSING, null, Instant.now().plus(ttl)));
        return true;
    }

    @Override
    public Optional<IdempotencyRecord> getRecord(String organizationId, String idempotencyKey) {
        String compositeKey = organizationId + ":" + idempotencyKey;
        IdempotencyEntry entry = store.get(compositeKey);
        if (entry == null || entry.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(new IdempotencyRecord(idempotencyKey, entry.state, entry.cachedResponseJson));
    }

    @Override
    public void markCompleted(String organizationId, String idempotencyKey, String responseJson, Duration ttl) {
        String compositeKey = organizationId + ":" + idempotencyKey;
        store.put(compositeKey, new IdempotencyEntry(IdempotencyState.COMPLETED, responseJson, Instant.now().plus(ttl)));
    }

    @Override
    public void markFailed(String organizationId, String idempotencyKey, Duration ttl) {
        String compositeKey = organizationId + ":" + idempotencyKey;
        store.put(compositeKey, new IdempotencyEntry(IdempotencyState.FAILED, null, Instant.now().plus(ttl)));
    }
}
