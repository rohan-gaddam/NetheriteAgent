package io.netherite.api;

import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.approval.service.ApprovalContinuationService;
import io.netherite.api.model.ApprovalQueueItem;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.OrganizationAuthorizationService;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/approvals")
@RequiredArgsConstructor
@Slf4j
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    @Lazy
    private final ApprovalContinuationService continuationService;

    @GetMapping("/{ticketId}")
    public ResponseEntity<?> getTicket(@PathVariable("ticketId") String ticketId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();

        UUID orgUuid;
        UUID ticketUuid;
        try {
            orgUuid = UUID.fromString(orgId);
            ticketUuid = UUID.fromString(ticketId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "TICKET_NOT_FOUND",
                    "message", "Approval ticket not found"));
        }

        return approvalRequestRepository.findByIdAndOrganizationId(ticketUuid, orgUuid)
                .map(entity -> ResponseEntity.ok((Object) toSafeResponse(entity)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "TICKET_NOT_FOUND",
                        "message", "Approval ticket not found")));
    }

    @GetMapping
    public ResponseEntity<?> listQueue(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "resource", required = false) String resource,
            @RequestParam(value = "actionType", required = false) String actionType) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid;
        try {
            orgUuid = UUID.fromString(orgId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "ERR_INVALID_PARAMETERS",
                    "message", "Invalid organization ID"));
        }

        String effectiveStatus = status != null ? status : "PENDING";

        List<ApprovalRequestEntity> entities;
        if (agentId != null) {
            UUID agentUuid;
            try {
                agentUuid = UUID.fromString(agentId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "ERR_INVALID_PARAMETERS",
                        "message", "Invalid agent ID"));
            }
            entities = approvalRequestRepository.findAllByOrganizationIdAndAgentIdAndStatus(orgUuid, agentUuid, effectiveStatus);
        } else if (resource != null) {
            entities = approvalRequestRepository.findAllByOrganizationIdAndTargetResourceAndStatus(orgUuid, resource, effectiveStatus);
        } else if (actionType != null) {
            entities = approvalRequestRepository.findAllByOrganizationIdAndActionTypeAndStatus(orgUuid, actionType, effectiveStatus);
        } else {
            entities = approvalRequestRepository.findAllByOrganizationIdAndStatus(orgUuid, effectiveStatus);
        }

        List<ApprovalQueueItem> items = entities.stream()
                .map(this::toQueueItem)
                .toList();

        return ResponseEntity.ok(items);
    }

    @PostMapping("/{ticketId}/approve")
    public ResponseEntity<?> approveTicket(
            @PathVariable("ticketId") String ticketId,
            @RequestBody(required = false) Map<String, String> body) {
        return resolveWithTenantCheck(ticketId, body, ApprovalStatus.APPROVED);
    }

    @PostMapping("/{ticketId}/reject")
    public ResponseEntity<?> rejectTicket(
            @PathVariable("ticketId") String ticketId,
            @RequestBody(required = false) Map<String, String> body) {
        return resolveWithTenantCheck(ticketId, body, ApprovalStatus.REJECTED);
    }

    private ResponseEntity<?> resolveWithTenantCheck(String ticketId, Map<String, String> body, ApprovalStatus decision) {
        var ctx = TenantContextHolder.getRequiredContext();
        String orgId = ctx.getOrganizationId();

        // WS6: approval authority belongs to the organization's single designated
        // authorized person. The previous HUMAN_APPROVER role check is removed
        // because role-based authorization no longer exists.
        //
        // The organization comes from the authenticated session, never the body.
        // An organization with no designated person resolves to nobody and every
        // decision is refused (fail closed).
        if (!organizationAuthorizationService.isAuthorizedPersonForTenant()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "ERR_FORBIDDEN",
                    "message", "Only this organization's designated authorized person may decide approvals"));
        }

        UUID orgUuid;
        UUID ticketUuid;
        try {
            orgUuid = UUID.fromString(orgId);
            ticketUuid = UUID.fromString(ticketId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "TICKET_NOT_FOUND",
                    "message", "Approval ticket not found"));
        }

        try {
            ApprovalRequestEntity entity = approvalRequestRepository.findByIdAndOrganizationId(ticketUuid, orgUuid)
                    .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                            "Approval ticket not found"));

            if (!"PENDING".equals(entity.getStatus())) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "ERR_INVALID_PARAMETERS",
                        "message", "Approval ticket is not in PENDING status"));
            }

            if (Instant.now().isAfter(entity.getExpiresAt())) {
                return ResponseEntity.status(410).body(Map.of(
                        "error", "ERR_TICKET_EXPIRED",
                        "message", "Approval ticket has expired"));
            }

            // Approver identity is derived from the authenticated session ONLY. A
            // client-supplied approverUserId is never trusted — it is ignored so one
            // user cannot attribute (or audit) a resolution as another.
            String approverUserId = ctx.getUserId();
            if (approverUserId == null || approverUserId.isBlank()) {
                return ResponseEntity.status(401).body(Map.of(
                        "error", "ERR_AUTHENTICATION_FAILED",
                        "message", "Authenticated approver identity is missing"));
            }
            String notes = body != null ? body.get("notes") : null;

            ApprovalTicket resolved = approvalService.resolveTicket(ticketId, decision, approverUserId, notes);

            if (decision == ApprovalStatus.APPROVED) {
                try {
                    continuationService.claimExecutionAndSchedule(ticketId);
                } catch (Exception e) {
                    log.error("Continuation claim triggered but failed for ticket {}: {}", ticketId, e.getMessage());
                }
            }

            return ResponseEntity.ok(resolved);
        } catch (NetheriteException e) {
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getErrorCode().getDefaultMessage()));
        }
    }

    private ApprovalQueueItem toQueueItem(ApprovalRequestEntity entity) {
        return ApprovalQueueItem.builder()
                .ticketId(entity.getId().toString())
                .organizationId(entity.getOrganizationId().toString())
                .agentId(entity.getAgentId().toString())
                .actionType(entity.getActionType())
                .targetResource(entity.getTargetResource())
                .status(entity.getStatus())
                .expiresAt(entity.getExpiresAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private Map<String, Object> toSafeResponse(ApprovalRequestEntity entity) {
        return Map.of(
                "ticketId", entity.getId().toString(),
                "organizationId", entity.getOrganizationId().toString(),
                "agentId", entity.getAgentId().toString(),
                "actionType", entity.getActionType(),
                "targetResource", entity.getTargetResource(),
                "status", entity.getStatus(),
                "expiresAt", entity.getExpiresAt().toString(),
                "createdAt", entity.getCreatedAt().toString());
    }
}
