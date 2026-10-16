package io.netherite.connectors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.service.ApprovalEventPublisher;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.audit.model.AuditRecord;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.context.model.DecisionContext;
import io.netherite.dashboard.service.HealthCheckService;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import io.netherite.state.spi.IdempotencyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 6 execution orchestrator.
 * Full chain: policy evaluation -> DENY/HUMAN_APPROVAL/ALLOW -> idempotency -> lock -> routing -> binding -> SSRF -> connector -> audit -> unlock.
 *
 * <p>Multi-node guarantees (Workstream 3): the idempotency claim is atomic in PostgreSQL,
 * the execution body is guarded by a cluster-wide Redisson lock, deterministic failures
 * are marked FAILED so clients get the failure instead of a silent 30-minute PROCESSING
 * window, and any Redis coordination outage fails closed to 503 - never an in-memory
 * fallback.
 */
@Slf4j
@Service
public class ConnectorExecutionService {

    private final PolicyEvaluationService policyEvaluationService;
    private final ResourceRoutingService resourceRoutingService;
    private final SsrfGuardValidator ssrfGuardValidator;
    private final List<ResourceConnector> connectors;
    private final IdempotencyStore idempotencyStore;
    private final DistributedLockManager lockManager;
    private final ApprovalService approvalService;
    private final ApprovalEventPublisher eventPublisher;
    private final AuditService auditService;
    private final DataRedactionService dataRedactionService;
    private final HealthCheckService healthCheckService;
    private final NetheriteDistributedProperties distributedProperties;
    private final ObjectMapper objectMapper;

    public ConnectorExecutionService(
            PolicyEvaluationService policyEvaluationService,
            ResourceRoutingService resourceRoutingService,
            SsrfGuardValidator ssrfGuardValidator,
            List<ResourceConnector> connectors,
            IdempotencyStore idempotencyStore,
            DistributedLockManager lockManager,
            ApprovalService approvalService,
            ApprovalEventPublisher eventPublisher,
            AuditService auditService,
            DataRedactionService dataRedactionService,
            HealthCheckService healthCheckService,
            NetheriteDistributedProperties distributedProperties) {
        this.policyEvaluationService = policyEvaluationService;
        this.resourceRoutingService = resourceRoutingService;
        this.ssrfGuardValidator = ssrfGuardValidator;
        this.connectors = connectors;
        this.idempotencyStore = idempotencyStore;
        this.lockManager = lockManager;
        this.approvalService = approvalService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.dataRedactionService = dataRedactionService;
        this.healthCheckService = healthCheckService;
        this.distributedProperties = distributedProperties;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Executes an authorized action through the full Phase 6 security chain.
     */
    public ConnectorExecutionResult execute(CanonicalAction action) {
        long overallStart = System.nanoTime();
        String executionId = action.getActionId();
        String orgId = action.getOrganizationId();
        String idempotencyKey = action.getIdempotencyKey();
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();

        log.info("Starting Phase 6 execution {} for action '{}' on resource '{}'",
                executionId, action.getActionType(), action.getTargetResource());

        // Step 1: Policy evaluation
        DecisionType decision;
        try {
            decision = policyEvaluationService.evaluateDecision(action);
        } catch (NetheriteException e) {
            auditEvent(orgId, executionId, action, DecisionType.DENY, null, null, System.nanoTime() - overallStart,
                    "POLICY_EVALUATION_FAILED");
            throw e;
        }

        // Step 2: DENY -> stop + audit
        if (decision == DecisionType.DENY) {
            log.warn("Execution {} blocked by DENY decision", executionId);
            auditEvent(orgId, executionId, action, DecisionType.DENY, null, null, System.nanoTime() - overallStart,
                    "DENY");
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                    "Policy decision prevents execution: DENY");
        }

        // Step 3: HUMAN_APPROVAL -> create ticket + stop + audit
        if (decision == DecisionType.HUMAN_APPROVAL) {
            log.info("Execution {} requires human approval", executionId);
            DecisionContext context = buildContext(action);
            List<String> triggeredPolicies = List.of("base-policy");
            ApprovalTicket ticket = approvalService.createTicket(context, triggeredPolicies);

            eventPublisher.publishEvent(orgId, "APPROVAL_CREATED", Map.of(
                    "ticketId", ticket.getTicketId(),
                    "actionType", action.getActionType(),
                    "targetResource", action.getTargetResource(),
                    "organizationId", orgId,
                    "agentId", action.getAgentId(),
                    "createdAt", ticket.getCreatedAt() != null ? ticket.getCreatedAt().toString() : java.time.Instant.now().toString()
            ));

            auditEvent(orgId, executionId, action, DecisionType.HUMAN_APPROVAL, null, null, System.nanoTime() - overallStart,
                    "HUMAN_APPROVAL_REQUESTED");
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                    "Action requires human approval. Ticket ID: " + ticket.getTicketId());
        }

        // Step 4: ALLOW -> idempotency check + atomic cluster-wide claim
        if (hasIdempotencyKey) {
            Optional<IdempotencyStore.IdempotencyRecord> existing = idempotencyStore.getRecord(orgId, idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyStore.IdempotencyRecord record = existing.get();
                if (record.state() == IdempotencyStore.IdempotencyState.COMPLETED) {
                    log.info("Execution {} idempotent replay - returning cached result", executionId);
                    auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                            "IDEMPOTENT_REPLAY");
                    try {
                        return objectMapper.readValue(record.cachedResponseJson(), ConnectorExecutionResult.class);
                    } catch (Exception e) {
                        return ConnectorExecutionResult.builder()
                                .successful(true)
                                .errorMessage("Idempotent replay - cached result unavailable")
                                .build();
                    }
                } else if (record.state() == IdempotencyStore.IdempotencyState.PROCESSING) {
                    log.warn("Execution {} duplicate concurrent request rejected", executionId);
                    auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                            "DUPLICATE_CONCURRENT_REJECTED");
                    throw new NetheriteException(SystemErrorCode.ERR_IDEMPOTENCY_CONFLICT,
                            "Duplicate request already processing under this idempotency key");
                } else if (record.state() == IdempotencyStore.IdempotencyState.FAILED) {
                    log.warn("Execution {} rejected - idempotency key previously failed (short window active)", executionId);
                    auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                            "DUPLICATE_PREVIOUS_FAILED");
                    throw new NetheriteException(SystemErrorCode.ERR_IDEMPOTENCY_CONFLICT,
                            "Idempotency key previously failed; retry after the failure window or use a new key");
                }
            }

            boolean claimed = idempotencyStore.tryClaim(orgId, idempotencyKey,
                    Duration.ofMinutes(distributedProperties.getIdempotency().getExecutionTtlMinutes()), executionId);
            if (!claimed) {
                log.warn("Execution {} idempotency claim failed", executionId);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                        "IDEMPOTENCY_CLAIM_FAILED");
                throw new NetheriteException(SystemErrorCode.ERR_IDEMPOTENCY_CONFLICT,
                        "Failed to claim idempotency key - possible concurrent duplicate");
            }
        }

        // Step 5: Distributed lock (cluster-wide serialization of the execution body)
        String lockKey = "execution:" + orgId + ":" + action.getAgentId() + ":" + action.getActionType();
        DistributedLock lock = null;
        try {
            Optional<DistributedLock> lockOpt;
            try {
                lockOpt = lockManager.tryAcquireLock(lockKey,
                        Duration.ofMillis(distributedProperties.getLock().getExecutionWaitMillis()),
                        Duration.ofMillis(distributedProperties.getLock().getExecutionLeaseMillis()));
            } catch (NetheriteException e) {
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                        "DISTRIBUTED_DEPENDENCY_UNAVAILABLE");
                throw e;
            }
            if (lockOpt.isEmpty()) {
                log.warn("Execution {} failed to acquire lock", executionId);
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                        "LOCK_ACQUISITION_FAILED");
                throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                        "Failed to acquire execution lock");
            }
            lock = lockOpt.get();

            // Step 6: Resolve resource route
            ResourceRoute route = resourceRoutingService.resolveRoute(action)
                    .orElseThrow(() -> {
                        failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                        auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, System.nanoTime() - overallStart,
                                "RESOURCE_NOT_FOUND");
                        return new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                                "Resource not found: " + action.getTargetResource());
                    });

            // Step 7: Verify agent-resource binding
            boolean bindingAuthorized = resourceRoutingService.isBindingAuthorized(
                    orgId, action.getAgentId(), route.getResourceId(), action.getActionType());
            if (!bindingAuthorized) {
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, System.nanoTime() - overallStart,
                        "BINDING_NOT_AUTHORIZED");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Agent is not authorized to access this resource");
            }

            // Step 8: SSRF validation
            String baseUrl = route.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, System.nanoTime() - overallStart,
                        "SSRF_NO_BASE_URL");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Resource route has no configured base URL");
            }

            URI targetUri;
            try {
                targetUri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, System.nanoTime() - overallStart,
                        "SSRF_INVALID_URL");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Resource has an invalid base URL configuration");
            }

            if (!ssrfGuardValidator.isUriPermitted(targetUri)) {
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, System.nanoTime() - overallStart,
                        "SSRF_BLOCKED");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Destination URL failed SSRF validation");
            }

            // Step 9: Select connector
            ResourceConnector connector = selectConnector(route.getConnectorType());
            if (connector == null) {
                failIdempotency(orgId, idempotencyKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, System.nanoTime() - overallStart,
                        "NO_CONNECTOR_AVAILABLE");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "No connector available for type: " + route.getConnectorType());
            }

            // Step 10: Build execution request with trusted data only.
            // The idempotency key is propagated so compliant external systems can deduplicate
            // a re-sent execution after a crash-recovery retry.
            ConnectorExecutionRequest executionRequest = ConnectorExecutionRequest.builder()
                    .executionId(executionId)
                    .idempotencyKey(hasIdempotencyKey ? idempotencyKey : null)
                    .action(action)
                    .route(route)
                    .timeoutMillis(30000)
                    .build();

            // Step 11: Execute
            log.info("Executing {} via connector '{}' against resource '{}'",
                    executionId, connector.getConnectorType(), route.getResourceName());
            auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, 0, "EXECUTION_STARTED");

            ConnectorExecutionResult result = connector.execute(executionRequest);
            long execDuration = (System.nanoTime() - overallStart) / 1_000_000;

            // Step 12: Audit result + update resource health
            if (result.isSuccessful()) {
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, result, execDuration,
                        "EXECUTION_SUCCEEDED");
                try {
                    healthCheckService.recordExecutionSuccess(
                            UUID.fromString(route.getResourceId()), UUID.fromString(orgId), route.getResourceName());
                } catch (Exception e) {
                    log.debug("Failed to update health on success: {}", e.getMessage());
                }
            } else {
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, result, execDuration,
                        "EXECUTION_FAILED");
                try {
                    String reason = result.getErrorMessage() != null ? result.getErrorMessage() : "Execution failed";
                    healthCheckService.recordExecutionFailure(
                            UUID.fromString(route.getResourceId()), UUID.fromString(orgId), route.getResourceName(), reason);
                } catch (Exception e) {
                    log.debug("Failed to update health on failure: {}", e.getMessage());
                }
            }

            // Step 13: Idempotency outcome. Success caches the result for replay. An
            // unsuccessful external call is marked FAILED with a short window so retries
            // surface the failure instead of re-executing (the crash-recovery case re-claims
            // after the window expires and re-sends with the same X-Netherite-Idempotency-Key).
            if (hasIdempotencyKey) {
                if (result.isSuccessful()) {
                    try {
                        String resultJson = objectMapper.writeValueAsString(result);
                        idempotencyStore.markCompleted(orgId, idempotencyKey, resultJson,
                                Duration.ofMinutes(distributedProperties.getIdempotency().getCompletedTtlMinutes()));
                    } catch (Exception e) {
                        log.warn("Failed to mark idempotency completed: {}", e.getMessage());
                    }
                } else {
                    failIdempotency(orgId, idempotencyKey, true);
                }
            }

            return result;

        } finally {
            if (lock != null) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.error("Failed to release lock {}: {}", lockKey, e.getMessage());
                }
            }
        }
    }

    private void failIdempotency(String orgId, String idempotencyKey, boolean hasIdempotencyKey) {
        if (!hasIdempotencyKey) {
            return;
        }
        try {
            idempotencyStore.markFailed(orgId, idempotencyKey,
                    Duration.ofSeconds(distributedProperties.getIdempotency().getFailedTtlSeconds()));
        } catch (Exception e) {
            log.warn("Failed to mark idempotency {} failed: {}", idempotencyKey, e.getMessage());
        }
    }

    private void auditEvent(String orgId, String requestId, CanonicalAction action,
                           DecisionType decision, ResourceRoute route,
                           ConnectorExecutionResult result, long durationMs, String eventType) {
        try {
            Map<String, Object> params = dataRedactionService.redactParameters(orgId,
                    action.getParameters() != null ? action.getParameters() : Map.of());

            Map<String, Object> redactedResult = null;
            if (result != null && result.getResponseBody() != null) {
                redactedResult = dataRedactionService.redactResult(orgId, result.getResponseBody());
            }

            AuditRecord record = AuditRecord.builder()
                    .organizationId(orgId)
                    .requestId(requestId)
                    .eventType(eventType)
                    .agentId(action.getAgentId())
                    .userId(action.getUserId())
                    .actionType(action.getActionType())
                    .targetResource(action.getTargetResource())
                    .decision(decision)
                    .redactedParameters(params)
                    .redactedExecutionResult(redactedResult)
                    .executionDurationMillis(durationMs)
                    .build();

            auditService.record(record);
        } catch (Exception e) {
            log.error("Failed to record audit event {}: {}", eventType, e.getMessage());
        }
    }

    private DecisionContext buildContext(CanonicalAction action) {
        return DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();
    }

    private ResourceConnector selectConnector(ConnectorType connectorType) {
        return connectors.stream()
                .filter(c -> c.getConnectorType() == connectorType)
                .findFirst()
                .orElse(null);
    }
}