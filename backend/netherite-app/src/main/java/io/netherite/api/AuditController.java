package io.netherite.api;

import io.netherite.api.model.AuditLogResponse;
import io.netherite.domain.entity.AuditLogEntity;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> listAuditLogs() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<AuditLogResponse> logs = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(orgUuid).stream()
                .map(this::toAuditLogResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(logs);
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<?> getAuditLog(@PathVariable("requestId") String requestId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<AuditLogEntity> entities = auditLogRepository.findAllByOrganizationIdAndRequestId(orgUuid, requestId);
        if (entities.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "AUDIT_LOG_NOT_FOUND",
                    "message", "Audit log not found"));
        }

        List<AuditLogResponse> logs = entities.stream()
                .map(this::toAuditLogResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(logs);
    }

    private AuditLogResponse toAuditLogResponse(AuditLogEntity entity) {
        return AuditLogResponse.builder()
                .id(entity.getId().toString())
                .organizationId(entity.getOrganizationId().toString())
                .requestId(entity.getRequestId())
                .eventType(entity.getEventType())
                .agentId(entity.getAgentId() != null ? entity.getAgentId().toString() : null)
                .userId(entity.getUserId())
                .actionType(entity.getActionType())
                .targetResource(entity.getTargetResource())
                .decision(entity.getDecision())
                .redactedParameters(entity.getRedactedParameters())
                .redactedExecutionResult(entity.getRedactedExecutionResult())
                .executionDurationMs(entity.getExecutionDurationMs())
                .currentRecordHash(entity.getCurrentRecordHash())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
