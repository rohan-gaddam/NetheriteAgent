package io.netherite.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.approval.spi.NotificationService;
import io.netherite.audit.model.AuditRecord;
import io.netherite.audit.spi.AuditService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.DecisionType;
import io.netherite.common.context.TenantContext;
import io.netherite.context.model.DecisionContext;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.security.context.TenantContextHolder;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JpaApprovalService implements ApprovalService {

    private final ApprovalRequestRepository approvalRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Duration TICKET_TTL = Duration.ofHours(24);

    @Override
    @Transactional
    public ApprovalTicket createTicket(DecisionContext context, List<String> triggeredPolicies) {
        if (context == null || context.getAction() == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Invalid decision context for approval ticket");
        }

        var action = context.getAction();
        UUID orgId;
        try {
            orgId = UUID.fromString(action.getOrganizationId());
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Invalid organization ID");
        }

        UUID agentId;
        try {
            agentId = UUID.fromString(action.getAgentId());
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Invalid agent ID");
        }

        Map<String, Object> actionMap;
        try {
            actionMap = objectMapper.convertValue(action, Map.class);
        } catch (Exception e) {
            actionMap = Map.of();
        }

        Map<String, Object> contextMap;
        try {
            contextMap = objectMapper.convertValue(context, Map.class);
        } catch (Exception e) {
            contextMap = Map.of();
        }

        ApprovalRequestEntity entity = ApprovalRequestEntity.builder()
                .organizationId(orgId)
                .agentId(agentId)
                .userId(action.getUserId())
                .actionType(action.getActionType())
                .targetResource(action.getTargetResource())
                .canonicalAction(actionMap)
                .decisionContext(contextMap)
                .triggeredPolicies(triggeredPolicies != null ? triggeredPolicies : List.of())
                .status("PENDING")
                .expiresAt(Instant.now().plus(TICKET_TTL))
                .build();

        entity = approvalRepository.save(entity);
        log.info("Created approval ticket {} for org={}, action={}, resource={}",
                entity.getId(), orgId, action.getActionType(), action.getTargetResource());

        try {
            auditService.record(AuditRecord.builder()
                    .organizationId(orgId.toString())
                    .requestId(entity.getId().toString())
                    .eventType("APPROVAL_CREATED")
                    .agentId(agentId.toString())
                    .actionType(action.getActionType())
                    .targetResource(action.getTargetResource())
                    .decision(DecisionType.HUMAN_APPROVAL)
                    .redactedParameters(Map.of())
                    .executionDurationMillis(0)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record audit event APPROVAL_CREATED: {}", e.getMessage());
        }

        try {
            notificationService.sendApprovalCreated(
                    orgId.toString(), entity.getId().toString(),
                    action.getActionType(), action.getTargetResource(),
                    agentId.toString());
        } catch (Exception e) {
            log.error("Failed to send approval created notification: {}", e.getMessage());
        }

        return toTicket(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalTicket> getTicket(String ticketId) {
        return approvalRepository.findById(UUID.fromString(ticketId))
                .map(this::toTicket);
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalTicket> getTicketForOrganization(String ticketId, UUID organizationId) {
        return approvalRepository.findByIdAndOrganizationId(UUID.fromString(ticketId), organizationId)
                .map(this::toTicket);
    }

    @Override
    @Transactional
    public ApprovalTicket resolveTicket(String ticketId, ApprovalStatus decision, String approverUserId, String notes) {
        ApprovalRequestEntity entity = approvalRepository.findById(UUID.fromString(ticketId))
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Approval ticket not found"));

        if (!"PENDING".equals(entity.getStatus())) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Approval ticket is not in PENDING status: " + entity.getStatus());
        }

        if (Instant.now().isAfter(entity.getExpiresAt())) {
            entity.setStatus("EXPIRED");
            entity.setResolvedAt(Instant.now());
            entity = approvalRepository.save(entity);
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                    "Approval ticket has expired");
        }

        entity.setStatus(decision.name());
        entity.setApproverDecision(decision.name());
        entity.setApproverNotes(notes);
        String effectiveApprover = resolveApproverId(approverUserId);
        if (effectiveApprover != null) {
            entity.setApproverUserIdentifier(effectiveApprover);
            try {
                entity.setApprovedByUserId(UUID.fromString(effectiveApprover));
            } catch (IllegalArgumentException e) {
                // approverUserId is a string identifier, not UUID - that's fine
            }
        }
        entity.setResolvedAt(Instant.now());

        try {
            entity = approvalRepository.save(entity);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Concurrent modification - ticket was already resolved");
        }

        log.info("Resolved approval ticket {} with decision={} by user={}", ticketId, decision, effectiveApprover);

        try {
            String eventType = decision == ApprovalStatus.APPROVED ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
            DecisionType decisionType = decision == ApprovalStatus.APPROVED ? DecisionType.ALLOW : DecisionType.DENY;
            auditService.record(AuditRecord.builder()
                    .organizationId(entity.getOrganizationId().toString())
                    .requestId(entity.getId().toString())
                    .eventType(eventType)
                    .agentId(entity.getAgentId().toString())
                    .userId(effectiveApprover)
                    .actionType(entity.getActionType())
                    .targetResource(entity.getTargetResource())
                    .decision(decisionType)
                    .redactedParameters(Map.of())
                    .executionDurationMillis(0)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record audit event for resolved ticket {}: {}", ticketId, e.getMessage());
        }

        try {
            notificationService.sendApprovalResolved(
                    entity.getOrganizationId().toString(), ticketId,
                    decision.name(), entity.getActionType(), entity.getTargetResource());
        } catch (Exception e) {
            log.error("Failed to send approval resolved notification: {}", e.getMessage());
        }

        return toTicket(entity);
    }

    @Override
    @Transactional
    public ApprovalTicket resolveTicketWithResult(String ticketId, ApprovalStatus decision, String approverUserId, String notes, String executionResult, String executionError) {
        ApprovalRequestEntity entity = approvalRepository.findById(UUID.fromString(ticketId))
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Approval ticket not found"));

        if (!"PENDING".equals(entity.getStatus())) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Approval ticket is not in PENDING status: " + entity.getStatus());
        }

        if (Instant.now().isAfter(entity.getExpiresAt())) {
            entity.setStatus("EXPIRED");
            entity.setResolvedAt(Instant.now());
            entity = approvalRepository.save(entity);
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                    "Approval ticket has expired");
        }

        entity.setStatus(decision.name());
        entity.setApproverDecision(decision.name());
        entity.setApproverNotes(notes);
        entity.setExecutionResult(executionResult);
        entity.setExecutionError(executionError);
        String effectiveApprover = resolveApproverId(approverUserId);
        if (effectiveApprover != null) {
            entity.setApproverUserIdentifier(effectiveApprover);
            try {
                entity.setApprovedByUserId(UUID.fromString(effectiveApprover));
            } catch (IllegalArgumentException e) {
                // approverUserId is a string identifier, not UUID - that's fine
            }
        }
        entity.setResolvedAt(Instant.now());

        try {
            entity = approvalRepository.save(entity);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Concurrent modification - ticket was already resolved");
        }

        log.info("Resolved approval ticket {} with decision={} by user={}, executionResult={}", ticketId, decision, effectiveApprover, executionResult != null ? "present" : "null");

        try {
            String eventType = decision == ApprovalStatus.APPROVED ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
            DecisionType decisionType = decision == ApprovalStatus.APPROVED ? DecisionType.ALLOW : DecisionType.DENY;
            auditService.record(AuditRecord.builder()
                    .organizationId(entity.getOrganizationId().toString())
                    .requestId(entity.getId().toString())
                    .eventType(eventType)
                    .agentId(entity.getAgentId().toString())
                    .userId(effectiveApprover)
                    .actionType(entity.getActionType())
                    .targetResource(entity.getTargetResource())
                    .decision(decisionType)
                    .redactedParameters(Map.of())
                    .executionDurationMillis(0)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record audit event for resolved ticket {}: {}", ticketId, e.getMessage());
        }

        try {
            notificationService.sendApprovalResolved(
                    entity.getOrganizationId().toString(), ticketId,
                    decision.name(), entity.getActionType(), entity.getTargetResource());
        } catch (Exception e) {
            log.error("Failed to send approval resolved notification for ticket {}: {}", ticketId, e.getMessage());
        }

        return toTicket(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalTicket> listPendingByOrganization(String organizationId) {
        UUID orgId = UUID.fromString(organizationId);
        return approvalRepository.findAllByOrganizationIdAndStatus(orgId, "PENDING")
                .stream()
                .map(this::toTicket)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalTicket> listByOrganizationAndStatus(String organizationId, ApprovalStatus status) {
        UUID orgId = UUID.fromString(organizationId);
        return approvalRepository.findAllByOrganizationIdAndStatus(orgId, status.name())
                .stream()
                .map(this::toTicket)
                .toList();
    }

    @Override
    @Transactional
    public ApprovalTicket expireTicket(String ticketId) {
        ApprovalRequestEntity entity = approvalRepository.findById(UUID.fromString(ticketId))
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Approval ticket not found"));

        if (!"PENDING".equals(entity.getStatus())) {
            return toTicket(entity);
        }

        entity.setStatus("EXPIRED");
        entity.setResolvedAt(Instant.now());

        try {
            entity = approvalRepository.save(entity);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            log.warn("Optimistic lock failure while expiring ticket {}", ticketId);
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Concurrent modification - ticket was already resolved");
        }

        log.info("Expired approval ticket {}", ticketId);
        return toTicket(entity);
    }

    /**
     * Resolves the authenticated approver identity. The caller (ApprovalController)
     * derives this from the tenant context; this fallback defends against internal
     * callers that opt out of identity, degrading to audit-userless rather than being
     * spoofable by request bodies.
     */
    private String resolveApproverId(String supplied) {
        if (supplied != null && !supplied.isBlank()) {
            return supplied;
        }
        TenantContext context = TenantContextHolder.getContext();
        if (context != null && context.getUserId() != null && !context.getUserId().isBlank()) {
            return context.getUserId();
        }
        return null;
    }

    private ApprovalTicket toTicket(ApprovalRequestEntity entity) {
        io.netherite.common.model.CanonicalAction action = null;
        try {
            action = objectMapper.convertValue(entity.getCanonicalAction(), io.netherite.common.model.CanonicalAction.class);
        } catch (Exception e) {
            // action remains null
        }

        DecisionContext context = null;
        try {
            context = objectMapper.convertValue(entity.getDecisionContext(), DecisionContext.class);
        } catch (Exception e) {
            // context remains null
        }

        return ApprovalTicket.builder()
                .ticketId(entity.getId().toString())
                .organizationId(entity.getOrganizationId().toString())
                .agentId(entity.getAgentId().toString())
                .actionType(entity.getActionType())
                .targetResource(entity.getTargetResource())
                .action(action)
                .context(context)
                .triggeredPolicies(entity.getTriggeredPolicies())
                .status(ApprovalStatus.valueOf(entity.getStatus()))
                .approverUserId(entity.getApproverUserIdentifier())
                .approverNotes(entity.getApproverNotes())
                .expiresAt(entity.getExpiresAt())
                .resolvedAt(entity.getResolvedAt())
                .createdAt(entity.getCreatedAt())
                .executionResult(entity.getExecutionResult())
                .executionError(entity.getExecutionError())
                .build();
    }
}
