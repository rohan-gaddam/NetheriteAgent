package io.netherite.audit.model;

import io.netherite.common.model.DecisionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable audit record stored in append-only tamper-evident hash chain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditRecord {
    private String auditId;
    private String organizationId;
    private String requestId;
    private String eventType;
    private String agentId;
    private String userId;
    private String actionType;
    private String targetResource;
    private DecisionType decision;
    private Map<String, Object> redactedParameters;
    private Map<String, Object> redactedExecutionResult;
    private long executionDurationMillis;
    private String prevRecordHash;
    private String currentRecordHash;
    private Instant createdAt;
}
