package io.netherite.state.service;

import io.netherite.state.spi.IdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Durable, cluster-wide idempotency store backed by PostgreSQL.
 *
 * <p>Uniqueness is enforced by the database primary key {@code (organization_id,
 * idempotency_key)}; {@code tryClaim} performs an {@code INSERT ... ON CONFLICT DO
 * NOTHING} and, on conflict, takes over a claim whose freshnness window has fully
 * elapsed (expired PROCESSING = crashed holder, expired terminal = retention reached).
 * All state transitions are single-statement and atomic, and survive process restart and
 * multi-replica concurrency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
        havingValue = "redis", matchIfMissing = true)
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_records (organization_id, idempotency_key, state, external_request_id, attempt_count, expires_at)
            VALUES (CAST(? AS uuid), ?, 'PROCESSING', ?, 1, ?)
            ON CONFLICT (organization_id, idempotency_key) DO NOTHING
            """;

    private static final String TAKEOVER_SQL = """
            UPDATE idempotency_records
            SET state = 'PROCESSING',
                cached_response = NULL,
                external_request_id = ?,
                attempt_count = CASE WHEN state = 'PROCESSING' THEN attempt_count + 1 ELSE 1 END,
                expires_at = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE organization_id = CAST(? AS uuid) AND idempotency_key = ? AND expires_at < CURRENT_TIMESTAMP
            """;

    private static final String SELECT_SQL = """
            SELECT idempotency_key, state, cached_response
            FROM idempotency_records
            WHERE organization_id = CAST(? AS uuid) AND idempotency_key = ? AND expires_at >= CURRENT_TIMESTAMP
            """;

    private static final String COMPLETE_SQL = """
            UPDATE idempotency_records
            SET state = 'COMPLETED', cached_response = ?, expires_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE organization_id = CAST(? AS uuid) AND idempotency_key = ? AND state = 'PROCESSING'
            """;

    private static final String FAIL_SQL = """
            UPDATE idempotency_records
            SET state = 'FAILED', cached_response = NULL, expires_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE organization_id = CAST(? AS uuid) AND idempotency_key = ? AND state = 'PROCESSING'
            """;

    private static final String PURGE_SQL = """
            WITH expired AS (
                SELECT ctid AS row_id
                FROM idempotency_records
                WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '5 seconds'
                ORDER BY expires_at ASC
                LIMIT ?
            )
            DELETE FROM idempotency_records WHERE ctid IN (SELECT row_id FROM expired) RETURNING 1
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean tryClaim(String organizationId, String idempotencyKey, Duration ttl) {
        return tryClaim(organizationId, idempotencyKey, ttl, null);
    }

    @Override
    public boolean tryClaim(String organizationId, String idempotencyKey, Duration ttl, String executionId) {
        Instant expiresAt = Instant.now().plus(ttl);
        OffsetDateTime expiresParam = OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC);

        int inserted = jdbcTemplate.update(CLAIM_SQL, organizationId, idempotencyKey, executionId, expiresParam);
        if (inserted == 1) {
            log.debug("Idempotency claim acquired org={} key={}", organizationId, idempotencyKey);
            return true;
        }

        int takenOver = jdbcTemplate.update(TAKEOVER_SQL, executionId, expiresParam, organizationId, idempotencyKey);
        if (takenOver == 1) {
            log.warn("Idempotency claim took over stale record org={} key={}", organizationId, idempotencyKey);
            return true;
        }

        log.debug("Idempotency claim denied org={} key={}", organizationId, idempotencyKey);
        return false;
    }

    @Override
    public Optional<IdempotencyRecord> getRecord(String organizationId, String idempotencyKey) {
        List<IdempotencyRecord> records = jdbcTemplate.query(SELECT_SQL,
                (rs, rowNum) -> mapRecord(rs),
                organizationId, idempotencyKey);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(records.get(0));
    }

    @Override
    public void markCompleted(String organizationId, String idempotencyKey, String responseJson, Duration ttl) {
        int updated = jdbcTemplate.update(COMPLETE_SQL,
                responseJson,
                OffsetDateTime.ofInstant(Instant.now().plus(ttl), ZoneOffset.UTC),
                organizationId, idempotencyKey);
        if (updated != 1) {
            log.warn("Idempotency completion skipped org={} key={} - claim no longer PROCESSING (expired/retaken/failed)",
                    organizationId, idempotencyKey);
        }
    }

    @Override
    public void markFailed(String organizationId, String idempotencyKey, Duration ttl) {
        int updated = jdbcTemplate.update(FAIL_SQL,
                OffsetDateTime.ofInstant(Instant.now().plus(ttl), ZoneOffset.UTC),
                organizationId, idempotencyKey);
        if (updated != 1) {
            log.warn("Idempotency failure mark skipped org={} key={} - claim no longer PROCESSING",
                    organizationId, idempotencyKey);
        }
    }

    /** Removes fully-expired rows (oldest first, bounded). Callers run on a schedule. */
    public int purgeExpired(int limit) {
        List<Integer> deleted = jdbcTemplate.queryForList(PURGE_SQL, Integer.class, limit);
        int removed = deleted.size();
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
        return removed;
    }

    private IdempotencyRecord mapRecord(ResultSet rs) throws SQLException {
        return new IdempotencyRecord(
                rs.getString("idempotency_key"),
                IdempotencyState.valueOf(rs.getString("state")),
                rs.getString("cached_response"));
    }
}