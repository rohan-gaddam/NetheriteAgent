package io.netherite.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.netherite.audit.model.AuditRecord;
import io.netherite.audit.spi.AuditService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.AuditChainHeadEntity;
import io.netherite.domain.entity.AuditLogEntity;
import io.netherite.domain.repository.AuditChainHeadRepository;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.observability.NetheriteMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only, tamper-evident audit store.
 *
 * <p>Chaining: every record's {@code current_record_hash} is a SHA-256 digest over a
 * length-canonical rendering of the record (including operator identity, redacted
 * parameters/result, timestamp) plus the previous record's hash. Writers serialize on the
 * per-organization {@code audit_chain_heads} anchor row (pessimistic write lock), so the
 * read-compute-write of the previous hash is race-free even with multiple replicas.</p>
 *
 * <p>Verification recomputes every digest and checks consecutive linkage, so both content
 * tampering (identity, payloads, decisions) and deletion/reordering are detectable.</p>
 *
 * <p>Fail-closed: SHA-256 absence, serialization failure or chain-anchor loss rejects the
 * write instead of silently degrading.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JpaAuditService implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuditChainHeadRepository chainHeadRepository;
    private final NetheriteMetrics metrics;

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override
    @Transactional
    public AuditRecord record(AuditRecord record) {
        // WS8: DB write failures are observed with a bounded counter before propagating.
        // Validation rejections (NetheriteException) are not store failures and are rethrown
        // untouched.
        try {
            return doRecord(record);
        } catch (NetheriteException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.auditWriteFailure();
            throw e;
        }
    }

    private AuditRecord doRecord(AuditRecord record) {
        if (record == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Audit record is null");
        }

        UUID orgId;
        try {
            orgId = UUID.fromString(record.getOrganizationId());
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Invalid organization ID in audit record");
        }

        // Writers serialize on the chain head so the previous-hash read is race-free across
        // replicas. A missing head is created lazily with the genesis hash.
        AuditChainHeadEntity head = chainHeadRepository.findByOrganizationIdForUpdate(orgId)
                .orElseGet(() -> insertGenesisHead(orgId));

        String prevHash = head.getLastHash();
        Instant createdAt = record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now();
        String currentHash = computeHash(record, prevHash, createdAt);

        AuditLogEntity entity = AuditLogEntity.builder()
                .organizationId(orgId)
                .requestId(record.getRequestId())
                .eventType(record.getEventType() != null ? record.getEventType() : "UNKNOWN")
                .agentId(record.getAgentId() != null ? safeUuid(record.getAgentId()) : null)
                .userId(record.getUserId())
                .actionType(record.getActionType())
                .targetResource(record.getTargetResource())
                .decision(record.getDecision() != null ? record.getDecision().name() : "UNKNOWN")
                .policyEvaluations(null)
                .redactedParameters(record.getRedactedParameters())
                .redactedExecutionResult(record.getRedactedExecutionResult())
                .executionDurationMs(record.getExecutionDurationMillis())
                .prevRecordHash(prevHash)
                .currentRecordHash(currentHash)
                .createdAt(createdAt)
                .build();

        entity = auditLogRepository.save(entity);

        head.setLastHash(currentHash);
        head.setUpdatedAt(Instant.now());
        chainHeadRepository.save(head);

        return toRecord(entity);
    }

    private AuditChainHeadEntity insertGenesisHead(UUID orgId) {
        try {
            AuditChainHeadEntity head = AuditChainHeadEntity.builder()
                    .organizationId(orgId)
                    .lastHash(AuditChainHeadEntity.GENESIS_HASH)
                    .updatedAt(Instant.now())
                    .build();
            AuditChainHeadEntity saved = chainHeadRepository.save(head);
            // Flush now so a concurrent genesis insert surfaces here (inside the guard)
            // instead of at transaction commit.
            chainHeadRepository.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Another replica created the head concurrently — re-select under the lock.
            return chainHeadRepository.findByOrganizationIdForUpdate(orgId)
                    .orElseThrow(() -> new IllegalStateException("Audit chain head creation raced for org " + orgId));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditRecord> findById(String organizationId, String auditId) {
        UUID orgId;
        try {
            orgId = UUID.fromString(organizationId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        return auditLogRepository.findById(UUID.fromString(auditId))
                .filter(e -> e.getOrganizationId().equals(orgId))
                .map(this::toRecord);
    }

    /**
     * Verifies a chain segment. For each record the digest is recomputed from its own
     * stored previous hash and compared with the stored current hash (content tampering is
     * detected), and consecutive records must link (reordering/deletion is detected).
     *
     * @param records chronologically ascending records of a single organization
     */
    @Override
    @Transactional(readOnly = true)
    public boolean verifyChainContinuity(String organizationId, List<AuditRecord> records) {
        if (records == null || records.isEmpty()) {
            return true;
        }

        for (int i = 0; i < records.size(); i++) {
            AuditRecord record = records.get(i);
            String expectedCurrent = computeHash(record, record.getPrevRecordHash(),
                    record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now());
            if (!expectedCurrent.equals(record.getCurrentRecordHash())) {
                log.warn("Audit record {} content hash mismatch in org {}: stored={}, recomputed={}",
                        record.getAuditId(), organizationId, record.getCurrentRecordHash(), expectedCurrent);
                return false;
            }
            if (i > 0) {
                String expectedPrev = records.get(i - 1).getCurrentRecordHash();
                if (!expectedPrev.equals(record.getPrevRecordHash())) {
                    log.warn("Audit chain link break in org {} at record {}: expected prev={}, found={}",
                            organizationId, record.getAuditId(), expectedPrev, record.getPrevRecordHash());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Length-canonical SHA-256 over every record field plus the previous hash. Length
     * prefixes remove concatenation ambiguity, and canonical JSON keeps Map payloads
     * deterministic across storage round-trips.
     */
    private String computeHash(AuditRecord record, String prevHash, Instant createdAt) {
        StringBuilder data = new StringBuilder();
        appendPart(data, record.getRequestId());
        appendPart(data, record.getEventType());
        appendPart(data, record.getOrganizationId());
        appendPart(data, record.getAgentId());
        appendPart(data, record.getUserId());
        appendPart(data, record.getActionType());
        appendPart(data, record.getTargetResource());
        appendPart(data, record.getDecision() != null ? record.getDecision().name() : null);
        appendPart(data, canonicalJson(record.getRedactedParameters()));
        appendPart(data, canonicalJson(record.getRedactedExecutionResult()));
        appendPart(data, Long.toString(record.getExecutionDurationMillis()));
        appendPart(data, createdAt != null ? createdAt.toString() : null);
        appendPart(data, prevHash);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest is unavailable", e);
        }
    }

    private void appendPart(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("0|");
            return;
        }
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        sb.append(raw.length).append('|').append(value);
    }

    private String canonicalJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize audit payload for hashing", e);
        }
    }

    private UUID safeUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AuditRecord toRecord(AuditLogEntity entity) {
        return AuditRecord.builder()
                .auditId(entity.getId().toString())
                .organizationId(entity.getOrganizationId().toString())
                .requestId(entity.getRequestId())
                .eventType(entity.getEventType())
                .agentId(entity.getAgentId() != null ? entity.getAgentId().toString() : null)
                .userId(entity.getUserId())
                .actionType(entity.getActionType())
                .targetResource(entity.getTargetResource())
                .decision(entity.getDecision() != null ?
                        io.netherite.common.model.DecisionType.valueOf(entity.getDecision()) : null)
                .redactedParameters(entity.getRedactedParameters())
                .redactedExecutionResult(entity.getRedactedExecutionResult())
                .executionDurationMillis(entity.getExecutionDurationMs())
                .prevRecordHash(entity.getPrevRecordHash())
                .currentRecordHash(entity.getCurrentRecordHash())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}