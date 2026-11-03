package io.netherite.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.security.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Executes the asynchronous continuation of an approved ticket on the replica that owns its
 * execution claim. Outcomes are persisted through the same claim lease, so a node crashing
 * mid-execution leaves the claim stale and another replica's recovery scheduler re-claims and
 * re-runs it (bounded by the attempt limit).
 */
@Component
@Slf4j
public class ApprovalExecutionRunner {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final AgentDomainService agentDomainService;
    private final ResourceDomainService resourceDomainService;
    private final ResourceRoutingService resourceRoutingService;
    private final ApprovalEventPublisher eventPublisher;
    private final ConnectorExecutionService connectorExecutionService;
    /**
     * Spring Boot's auto-configured mapper. It must register {@code JavaTimeModule}: the ticket's
     * stored action carries an {@code Instant timestamp}, which a bare {@code new ObjectMapper()}
     * cannot deserialize into {@link CanonicalAction}.
     */
    private final ObjectMapper objectMapper;

    public ApprovalExecutionRunner(
            ApprovalRequestRepository approvalRequestRepository,
            AgentDomainService agentDomainService,
            ResourceDomainService resourceDomainService,
            ResourceRoutingService resourceRoutingService,
            ApprovalEventPublisher eventPublisher,
            ConnectorExecutionService connectorExecutionService,
            ObjectMapper objectMapper) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.agentDomainService = agentDomainService;
        this.resourceDomainService = resourceDomainService;
        this.resourceRoutingService = resourceRoutingService;
        this.eventPublisher = eventPublisher;
        this.connectorExecutionService = connectorExecutionService;
        this.objectMapper = objectMapper;
    }

    @Async
    public void execute(String ticketId, String nodeId) {
        UUID ticketUuid;
        try {
            ticketUuid = UUID.fromString(ticketId);
        } catch (IllegalArgumentException e) {
            log.warn("Approval continuation skipped - invalid ticket id {}", ticketId);
            return;
        }

        ApprovalRequestEntity entity = approvalRequestRepository.findById(ticketUuid).orElse(null);
        if (entity == null || !"APPROVED".equals(entity.getStatus())) {
            log.info("Approval continuation skipped for ticket={} - no longer APPROVED", ticketId);
            return;
        }

        String orgId = entity.getOrganizationId().toString();
        // WS6: SYSTEM principal, no roles. This internal continuation runs the
        // agent's already-approved action and is not an interactive endpoint, so
        // it is authorized by the approval ticket itself rather than by an
        // organization designation.
        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(orgId)
                .agentId(entity.getAgentId().toString())
                .principalType("SYSTEM")
                .build());

        try {
            log.info("Starting approval continuation for ticket={} on node={}", ticketId, nodeId);

            // Deterministic pre-checks - when these fail the ticket is terminal, not retryable.
            AgentEntity agent = agentDomainService.getAgent(entity.getOrganizationId(), entity.getAgentId());
            if (agent == null || !"ACTIVE".equals(agent.getStatus())) {
                failDeterministic(ticketUuid, nodeId, orgId, "Agent is no longer active");
                return;
            }

            CanonicalAction originalAction;
            try {
                originalAction = objectMapper.convertValue(entity.getCanonicalAction(), CanonicalAction.class);
            } catch (Exception e) {
                failDeterministic(ticketUuid, nodeId, orgId, "Stored canonical action could not be deserialized");
                return;
            }
            if (originalAction == null) {
                failDeterministic(ticketUuid, nodeId, orgId, "Original action not found in approval ticket");
                return;
            }

            // Resolve through the same trusted, tenant-scoped routing used by the
            // initial execution. CanonicalAction.getTargetResource() is the resource
            // NAME (gateway tickets store e.g. "SSELIVE_HIGH_API"), so parsing it
            // as a UUID fails for every gateway-created ticket (IllegalArgument
            // for names, NullPointer for missing values). The route carries the
            // authoritative resource ID; an unresolvable route is terminal.
            ResourceRoute route = resourceRoutingService.resolveRoute(originalAction).orElse(null);
            if (route == null || route.getResourceId() == null) {
                failDeterministic(ticketUuid, nodeId, orgId, "Target resource is no longer available");
                return;
            }

            UUID resourceUuid;
            try {
                resourceUuid = UUID.fromString(route.getResourceId());
            } catch (IllegalArgumentException | NullPointerException e) {
                failDeterministic(ticketUuid, nodeId, orgId, "Invalid target resource ID in original action");
                return;
            }

            ResourceEntity resource = resourceDomainService.getResource(entity.getOrganizationId(), resourceUuid);
            if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
                failDeterministic(ticketUuid, nodeId, orgId, "Target resource is no longer active");
                return;
            }

            boolean bindingAuthorized = resourceRoutingService.isBindingAuthorized(
                    orgId, entity.getAgentId().toString(), resourceUuid.toString(), originalAction.getActionType());
            if (!bindingAuthorized) {
                failDeterministic(ticketUuid, nodeId, orgId, "Agent-resource binding no longer authorized");
                return;
            }

            ConnectorExecutionResult result;
            try {
                // The approved ticket authorises this exact action: the execution path re-validates
                // the ticket (status, organization, action identity) and satisfies the
                // HUMAN_APPROVAL gate from that prior decision instead of raising a new ticket.
                result = connectorExecutionService.executeApproved(originalAction, ticketId);
            } catch (NetheriteException e) {
                if (isTransient(e)) {
                    releaseTransient(ticketUuid, nodeId, orgId,
                            "Transient infrastructure failure, retry scheduled: " + e.getMessage());
                } else {
                    failDeterministic(ticketUuid, nodeId, orgId,
                            "Approval execution failed: " + e.getMessage());
                }
                return;
            } catch (Exception e) {
                releaseTransient(ticketUuid, nodeId, orgId,
                        "Unexpected execution error, retry scheduled: " + e.getMessage());
                return;
            }

            String resultJson;
            try {
                resultJson = objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                resultJson = "{\"error\":\"Failed to serialize result\"}";
            }

            int completed = approvalRequestRepository.completeExecution(ticketUuid, nodeId, resultJson);
            if (completed == 1) {
                eventPublisher.publishEvent(orgId, "APPROVAL_EXECUTED", Map.of(
                        "ticketId", ticketId,
                        "status", "COMPLETED",
                        "successful", result.isSuccessful()
                ));
                log.info("Approval continuation completed for ticket={} on node={} (successful={})",
                        ticketId, nodeId, result.isSuccessful());
            } else {
                log.warn("Approval continuation claim lost for ticket={} - another replica owns it", ticketId);
            }

        } finally {
            TenantContextHolder.clear();
        }
    }

    private boolean isTransient(NetheriteException e) {
        return e.getErrorCode() == SystemErrorCode.ERR_SYSTEM_UNAVAILABLE
                || e.getErrorCode() == SystemErrorCode.ERR_CONNECTOR_TIMEOUT;
    }

    private void releaseTransient(UUID ticketUuid, String nodeId, String orgId, String reason) {
        log.warn("Approval continuation released for ticket={}: {}", ticketUuid, reason);
        approvalRequestRepository.releaseExecutionClaim(ticketUuid, nodeId);
        eventPublisher.publishEvent(orgId, "APPROVAL_CONTINUATION_RETRY_SCHEDULED", Map.of(
                "ticketId", ticketUuid.toString(),
                "reason", reason
        ));
    }

    private void failDeterministic(UUID ticketUuid, String nodeId, String orgId, String error) {
        log.warn("Approval continuation failed deterministically for ticket={}: {}", ticketUuid, error);
        approvalRequestRepository.failExecution(ticketUuid, nodeId, error);
        eventPublisher.publishEvent(orgId, "APPROVAL_CONTINUATION_FAILED", Map.of(
                "ticketId", ticketUuid.toString(),
                "error", error
        ));
    }
}