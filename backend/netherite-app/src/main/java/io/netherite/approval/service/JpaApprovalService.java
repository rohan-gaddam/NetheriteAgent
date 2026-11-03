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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ApprovalEventPublisher eventPublisher;
    /**
     * Spring Boot's auto-configured mapper. It registers {@code JavaTimeModule}, so the required
     * {@code Instant timestamp} of a canonical action round-trips through the ticket's JSON column.
     * The previous bare {@code new ObjectMapper()} had no JSR-310 support: serialising the timestamp
     * threw, the throw was swallowed, and every ticket persisted an EMPTY canonical action.
     */
    private final ObjectMapper objectMapper;

    private static final Duration TICKET_TTL = Duration.ofHours(24);

    /**
     * Fields a stored canonical action must carry to remain executable. {@code CanonicalAction}
     * is annotated {@code @JsonInclude(NON_NULL)}, so a null or blank required field is simply
     * absent from the serialized map - an empty map means the action is unusable.
     */
    private static final List<String> REQUIRED_CANONICAL_ACTION_FIELDS = List.of(
            "actionId", "organizationId", "agentId", "actionType",
            "targetResource", "timestamp", "parameters");

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

        Map<String, Object> actionMap = canonicalizeOrThrow(action, "canonical action");
        Map<String, Object> contextMap = canonicalizeOrThrow(context, "decision context");
        requireCompleteAction(actionMap);

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

        publishDecisionAfterCommit(entity.getOrganizationId().toString(), ticketId, decision);

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

        publishDecisionAfterCommit(entity.getOrganizationId().toString(), ticketId, decision);

        return toTicket(entity);
    }

    /**
     * Broadcasts the approval decision to SSE subscribers of the ticket's organization only,
     * and only after the decision transaction commits. A decision that later rolls back is
     * never broadcast. Payload is minimal (ticketId + status) to match the existing
     * frontend contract; notes, approver identity, and action content are never exposed.
     */
    private void publishDecisionAfterCommit(String organizationId, String ticketId, ApprovalStatus decision) {
        String eventType = decision == ApprovalStatus.APPROVED ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
        Map<String, String> payload = Map.of("ticketId", ticketId, "status", decision.name());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        eventPublisher.publishEvent(organizationId, eventType, payload);
                    } catch (Exception e) {
                        log.error("Failed to publish SSE decision event for ticket {}: {}", ticketId, e.getMessage());
                    }
                }
            });
        } else {
            try {
                eventPublisher.publishEvent(organizationId, eventType, payload);
            } catch (Exception e) {
                log.error("Failed to publish SSE decision event for ticket {}: {}", ticketId, e.getMessage());
            }
        }
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

    /**
     * Serializes a value into the ticket's JSON column, surfacing any failure. Swallowing this
     * error is what previously persisted an EMPTY canonical action: a ticket could be approved by a
     * human and then be un-executable, with nothing in the logs pointing at the cause.
     */
    private Map<String, Object> canonicalizeOrThrow(Object value, String what) {
        Map<String, Object> converted;
        try {
            converted = objectMapper.convertValue(value, Map.class);
        } catch (Exception e) {
            log.error("Failed to serialize approval {}: {}", what, e.getMessage());
            throw new NetheriteException(SystemErrorCode.ERR_CANONICALIZATION_FAILED,
                    "Approval " + what + " could not be serialized into a valid canonical format");
        }
        if (converted == null) {
            log.error("Serialization of approval {} produced a null map", what);
            throw new NetheriteException(SystemErrorCode.ERR_CANONICALIZATION_FAILED,
                    "Approval " + what + " could not be serialized into a valid canonical format");
        }
        return converted;
    }

    /**
     * Guarantees a ticket is never persisted with an empty or incomplete canonical action. An
     * approval of an action that cannot be reconstructed is worthless, so this fails closed before
     * the row is written rather than storing a ticket that can never execute.
     */
    private void requireCompleteAction(Map<String, Object> actionMap) {
        List<String> missing = REQUIRED_CANONICAL_ACTION_FIELDS.stream()
                .filter(field -> {
                    Object value = actionMap.get(field);
                    if (value == null) {
                        return true;
                    }
                    return value instanceof String string && string.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            log.error("Refusing to create approval ticket - canonical action is missing required fields {}", missing);
            throw new NetheriteException(SystemErrorCode.ERR_CANONICALIZATION_FAILED,
                    "Approval ticket cannot be created: canonical action is incomplete (missing " + missing + ")");
        }
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
