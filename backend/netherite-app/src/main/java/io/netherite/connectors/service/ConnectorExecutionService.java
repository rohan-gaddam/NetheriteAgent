package io.netherite.connectors.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import io.netherite.security.key.CredentialEncryptionService;
import io.netherite.common.context.TenantContext;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import io.netherite.state.spi.IdempotencyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * Optional because the encryption key is injected externally. When a resource carries
     * encrypted credentials and this dependency is absent, execution fails closed.
     */
    @Autowired(required = false)
    private CredentialEncryptionService credentialEncryptionService;

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
        return executeInternal(action, null);
    }

    /**
     * Executes the continuation of an already-APPROVED ticket. The ticket is the authorization:
     * it must exist, be APPROVED, belong to the action's organization, and name exactly this
     * action. Policy is still evaluated and a DENY decision still blocks; only the HUMAN_APPROVAL
     * gate is treated as satisfied by the human's earlier decision. This is deliberately narrow -
     * it does not create a general policy bypass, and it never creates a second approval ticket.
     */
    public ConnectorExecutionResult executeApproved(CanonicalAction action, String approvalTicketId) {
        verifyApprovedTicket(action, approvalTicketId);
        return executeInternal(action, approvalTicketId);
    }

    /**
     * Fails closed unless the supplied ticket is a valid, human-issued authorization for exactly
     * this action. Any mismatch - missing ticket, not APPROVED, wrong organization, or a different
     * (or unreadable) canonical action - is rejected before anything is executed.
     */
    private void verifyApprovedTicket(CanonicalAction action, String approvalTicketId) {
        if (approvalTicketId == null || approvalTicketId.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "An approval ticket is required to execute an approved action");
        }
        ApprovalTicket ticket = approvalService.getTicket(approvalTicketId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Approval ticket not found"));
        if (ticket.getStatus() != ApprovalStatus.APPROVED) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Approval ticket is not in APPROVED state: " + ticket.getStatus());
        }
        if (!Objects.equals(ticket.getOrganizationId(), action.getOrganizationId())) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Approval ticket does not authorize this organization");
        }
        CanonicalAction approvedAction = ticket.getAction();
        if (approvedAction == null || !Objects.equals(approvedAction, action)) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Approval ticket does not authorize this exact action");
        }
    }

    private long elapsedMillis(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private ConnectorExecutionResult executeInternal(CanonicalAction action, String approvedTicketId) {
        long overallStart = System.nanoTime();
        String executionId = action.getActionId();
        String orgId = action.getOrganizationId();
        String agentId = resolveAuthenticatedAgentId(action);
        String idempotencyKey = action.getIdempotencyKey();
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        String agentScopedKey = hasIdempotencyKey ? agentId + ":" + idempotencyKey : null;

        log.info("Starting Phase 6 execution {} for action '{}' on resource '{}'",
                executionId, action.getActionType(), action.getTargetResource());

        // Step 1: Policy evaluation
        DecisionType decision;
        try {
            decision = policyEvaluationService.evaluateDecision(action);
        } catch (NetheriteException e) {
            NetheriteException withAudit = e;
            try {
                auditEventRequired(orgId, executionId, action, DecisionType.DENY, null, null, elapsedMillis(overallStart),
                        "POLICY_EVALUATION_FAILED");
            } catch (Exception auditEx) {
                withAudit = new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Policy evaluation failed: " + e.getMessage(), auditEx);
            }
            throw withAudit;
        }

        // Step 2: DENY -> stop + audit
        if (decision == DecisionType.DENY) {
            log.warn("Execution {} blocked by DENY decision", executionId);
            try {
                auditEventRequired(orgId, executionId, action, DecisionType.DENY, null, null, elapsedMillis(overallStart),
                        "DENY");
            } catch (Exception auditEx) {
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Policy decision prevents execution: DENY", auditEx);
            }
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                    "Policy decision prevents execution: DENY");
        }

        // Step 3: HUMAN_APPROVAL -> satisfy from an existing approval, otherwise create a ticket
        if (decision == DecisionType.HUMAN_APPROVAL) {
            if (approvedTicketId != null) {
                // Continuation of an APPROVED ticket: the gate is already satisfied by the human's
                // decision. Record it and continue - never create a duplicate approval ticket.
                log.info("Execution {} authorized by existing approval ticket {}", executionId, approvedTicketId);
                auditEvent(orgId, executionId, action, DecisionType.HUMAN_APPROVAL, null, null,
                        elapsedMillis(overallStart), "HUMAN_APPROVAL_ALREADY_GRANTED");
            } else {
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

                NetheriteException approvalRequired;
                try {
                    auditEventRequired(orgId, executionId, action, DecisionType.HUMAN_APPROVAL, null, null,
                            elapsedMillis(overallStart), "HUMAN_APPROVAL_REQUESTED");
                    approvalRequired = new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                            "Action requires human approval. Ticket ID: " + ticket.getTicketId());
                } catch (Exception auditEx) {
                    approvalRequired = new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                            "Action requires human approval. Ticket ID: " + ticket.getTicketId(), auditEx);
                }
                throw approvalRequired;
            }
        }

        // Step 4: ALLOW -> idempotency check + atomic cluster-wide claim
        if (hasIdempotencyKey) {
            Optional<IdempotencyStore.IdempotencyRecord> existing = idempotencyStore.getRecord(orgId, agentScopedKey);
            if (existing.isPresent()) {
                IdempotencyStore.IdempotencyRecord record = existing.get();
                if (record.state() == IdempotencyStore.IdempotencyState.COMPLETED) {
                    log.info("Execution {} idempotent replay - returning cached result", executionId);
                    auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
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
                    auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
                            "DUPLICATE_CONCURRENT_REJECTED");
                    throw new NetheriteException(SystemErrorCode.ERR_IDEMPOTENCY_CONFLICT,
                            "Duplicate request already processing under this idempotency key");
                } else if (record.state() == IdempotencyStore.IdempotencyState.FAILED) {
                    log.warn("Execution {} rejected - idempotency key previously failed (short window active)", executionId);
                    auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
                            "DUPLICATE_PREVIOUS_FAILED");
                    throw new NetheriteException(SystemErrorCode.ERR_IDEMPOTENCY_CONFLICT,
                            "Idempotency key previously failed; retry after the failure window or use a new key");
                }
            }

            boolean claimed = idempotencyStore.tryClaim(orgId, agentScopedKey,
                    Duration.ofMinutes(distributedProperties.getIdempotency().getExecutionTtlMinutes()), executionId);
            if (!claimed) {
                log.warn("Execution {} idempotency claim failed", executionId);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
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
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
                        "DISTRIBUTED_DEPENDENCY_UNAVAILABLE");
                throw e;
            }
            if (lockOpt.isEmpty()) {
                log.warn("Execution {} failed to acquire lock", executionId);
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
                        "LOCK_ACQUISITION_FAILED");
                throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                        "Failed to acquire execution lock");
            }
            lock = lockOpt.get();

            // Step 6: Resolve resource route
            ResourceRoute route = resourceRoutingService.resolveRoute(action)
                    .orElseThrow(() -> {
                        failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                        auditEvent(orgId, executionId, action, DecisionType.ALLOW, null, null, elapsedMillis(overallStart),
                                "RESOURCE_NOT_FOUND");
                        return new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                                "Resource not found: " + action.getTargetResource());
                    });

            // Step 7: Verify agent-resource binding
            boolean bindingAuthorized = resourceRoutingService.isBindingAuthorized(
                    orgId, action.getAgentId(), route.getResourceId(), action.getActionType());
            if (!bindingAuthorized) {
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, elapsedMillis(overallStart),
                        "BINDING_NOT_AUTHORIZED");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Agent is not authorized to access this resource");
            }

            // Step 8: SSRF validation
            String baseUrl = route.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, elapsedMillis(overallStart),
                        "SSRF_NO_BASE_URL");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Resource route has no configured base URL");
            }

            URI targetUri;
            try {
                targetUri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, elapsedMillis(overallStart),
                        "SSRF_INVALID_URL");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Resource has an invalid base URL configuration");
            }

            if (!ssrfGuardValidator.isUriPermitted(targetUri)) {
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, elapsedMillis(overallStart),
                        "SSRF_BLOCKED");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Destination URL failed SSRF validation");
            }

            // Step 9: Select connector
            ResourceConnector connector = selectConnector(route.getConnectorType());
            if (connector == null) {
                failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
                auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null, elapsedMillis(overallStart),
                        "NO_CONNECTOR_AVAILABLE");
                throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "No connector available for type: " + route.getConnectorType());
            }

            // Step 10: Build execution request with trusted data only.
            // The idempotency key is propagated so compliant external systems can deduplicate
            // a re-sent execution after a crash-recovery retry.
            // Credentials are decrypted server-side here - after the binding has been
            // authorized and immediately before connector execution - and are never accepted
            // from the client request or returned to it.
            Map<String, String> resolvedCredentials =
                    resolveCredentials(orgId, agentScopedKey, hasIdempotencyKey, executionId, action, route, overallStart);

            var builder = ConnectorExecutionRequest.builder()
                    .executionId(executionId)
                    .idempotencyKey(hasIdempotencyKey ? idempotencyKey : null)
                    .action(action)
                    .route(route)
                    .resolvedCredentials(resolvedCredentials)
                    .timeoutMillis(30000);

            ConnectorExecutionRequest executionRequest = builder.build();

            // Step 11: Execute - fail-closed: if the EXECUTION_STARTED audit record cannot be
            // persisted, the connector is never invoked.
            log.info("Executing {} via connector '{}' against resource '{}'",
                    executionId, connector.getConnectorType(), route.getResourceName());
            auditEventRequired(orgId, executionId, action, DecisionType.ALLOW, route, null, 0, "EXECUTION_STARTED");

            ConnectorExecutionResult result = connector.execute(executionRequest);
            long execDuration = elapsedMillis(overallStart);

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
                        idempotencyStore.markCompleted(orgId, agentScopedKey, resultJson,
                                Duration.ofMinutes(distributedProperties.getIdempotency().getCompletedTtlMinutes()));
                    } catch (Exception e) {
                        log.warn("Failed to mark idempotency completed: {}", e.getMessage());
                    }
                } else {
                    failIdempotency(orgId, agentScopedKey, true);
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

    /**
     * Decrypts the resource's stored credential record and returns it only after the
     * binding has already been authorized. Any missing key, malformed ciphertext, or
     * unreadable credential document fails closed before the connector is invoked.
     */
    private Map<String, String> resolveCredentials(String orgId, String agentScopedKey,
                                                   boolean hasIdempotencyKey, String executionId,
                                                   CanonicalAction action, ResourceRoute route,
                                                   long overallStart) {
        String encrypted = route.getCredentialReference();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }

        if (credentialEncryptionService == null) {
            failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
            auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null,
                    elapsedMillis(overallStart), "CREDENTIAL_RESOLUTION_FAILED");
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Credential encryption is not configured for this resource");
        }

        try {
            String plaintext = credentialEncryptionService.decrypt(encrypted);
            Map<String, String> credentials = objectMapper.readValue(
                    plaintext, new TypeReference<Map<String, String>>() { });
            if (credentials == null || credentials.isEmpty()) {
                throw new IllegalArgumentException("Credential record is empty");
            }
            return credentials;
        } catch (Exception e) {
            failIdempotency(orgId, agentScopedKey, hasIdempotencyKey);
            log.warn("Failed to resolve credentials for resource route '{}' - failing closed",
                    route.getResourceName());
            auditEvent(orgId, executionId, action, DecisionType.ALLOW, route, null,
                    elapsedMillis(overallStart), "CREDENTIAL_RESOLUTION_FAILED");
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Failed to resolve credentials for resource route", e);
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

    /**
     * Records an audit event on an execution-critical path. If persistence fails, the
     * exception is thrown so the caller can fail closed (e.g. never invoke the connector
     * when EXECUTION_STARTED cannot be durably recorded).
     */
    private void auditEventRequired(String orgId, String requestId, CanonicalAction action,
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
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Failed to record audit event " + eventType, e);
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

    /**
     * Resolves the agent identity from the authenticated execution context
     * ({@link TenantContextHolder}), never from an untrusted request field. Falls back to
     * the action's agentId only when no security context is active (e.g. unit tests).
     */
    private String resolveAuthenticatedAgentId(CanonicalAction action) {
        TenantContext context = TenantContextHolder.getContext();
        if (context != null && context.getAgentId() != null && !context.getAgentId().isBlank()) {
            return context.getAgentId();
        }
        return action.getAgentId();
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