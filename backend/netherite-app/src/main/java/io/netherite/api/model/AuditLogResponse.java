package io.netherite.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private String id;
    private String organizationId;
    private String requestId;
    private String eventType;
    private String agentId;
    private String userId;
    private String actionType;
    private String targetResource;
    private String decision;
    private Map<String, Object> redactedParameters;
    private Map<String, Object> redactedExecutionResult;
    private long executionDurationMs;
    private String currentRecordHash;
    private Instant createdAt;
}
