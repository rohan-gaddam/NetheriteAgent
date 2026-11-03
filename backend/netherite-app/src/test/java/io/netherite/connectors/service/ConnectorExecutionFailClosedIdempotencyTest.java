package io.netherite.connectors.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.dashboard.service.HealthCheckService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import io.netherite.state.spi.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for fail-closed execution auditing and agent-scoped idempotency.
 *
 * <p>Covers:
 * <ul>
 *   <li>Failure to persist {@code EXECUTION_STARTED} prevents connector invocation.</li>
 *   <li>Audit failures on rejection paths are preserved as suppressed exceptions.</li>
 *   <li>Two agents in one organization using the same caller-provided idempotency key
 *       do not collide (agent-scoped composite key).</li>
 *   <li>Same-agent idempotency and concurrent-claim behavior remain correct.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConnectorExecutionFailClosedIdempotencyTest {

    @Mock private PolicyEvaluationService policyEvaluationService;
    @Mock private ResourceRoutingService resourceRoutingService;
    @Mock private SsrfGuardValidator ssrfGuardValidator;
    @Mock private IdempotencyStore idempotencyStore;
    @Mock private DistributedLockManager lockManager;
    @Mock private AuditService auditService;
    @Mock private DataRedactionService dataRedactionService;
    @Mock private HealthCheckService healthCheckService;
    @Mock private NetheriteDistributedProperties distributedProperties;
    @Mock private NetheriteDistributedProperties.Idempotency idempotencyProps;
    @Mock private NetheriteDistributedProperties.Lock lockProps;

    private final ResourceConnector connector = mock(ResourceConnector.class);

    private ConnectorExecutionService service;
    private UUID orgId;
    private UUID agentIdA;
    private UUID agentIdB;

    @BeforeEach
    void setUp() {
        service = new ConnectorExecutionService(
                policyEvaluationService, resourceRoutingService, ssrfGuardValidator,
                List.of(connector), idempotencyStore, lockManager, null, null,
                auditService, dataRedactionService, healthCheckService,
                distributedProperties);
        orgId = UUID.randomUUID();
        agentIdA = UUID.randomUUID();
        agentIdB = UUID.randomUUID();

        lenient().when(distributedProperties.getLock()).thenReturn(lockProps);
        lenient().when(lockProps.getExecutionWaitMillis()).thenReturn(1_000L);
        lenient().when(lockProps.getExecutionLeaseMillis()).thenReturn(1_000L);
        lenient().when(distributedProperties.getIdempotency()).thenReturn(idempotencyProps);
        lenient().when(idempotencyProps.getExecutionTtlMinutes()).thenReturn(10L);
        lenient().when(idempotencyProps.getCompletedTtlMinutes()).thenReturn(60L);
        lenient().when(idempotencyProps.getFailedTtlSeconds()).thenReturn(300L);
        lenient().when(ssrfGuardValidator.isUriPermitted(any())).thenReturn(true);
        lenient().when(lockManager.tryAcquireLock(anyString(), any(), any()))
                .thenReturn(Optional.of(mock(DistributedLock.class)));
        lenient().when(idempotencyStore.tryClaim(anyString(), anyString(), any(), any())).thenReturn(true);
        lenient().when(connector.getConnectorType()).thenReturn(ConnectorType.HTTP_REST);
        lenient().when(connector.execute(any(ConnectorExecutionRequest.class)))
                .thenReturn(ConnectorExecutionResult.builder().successful(true).statusCode(200).build());
    }

    private CanonicalAction action(UUID agentId) {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(orgId.toString())
                .agentId(agentId.toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of("amount", 100))
                .idempotencyKey("caller-key-123")
                .timestamp(Instant.now())
                .build();
    }

    private void stubSuccessfulRoute() {
        ResourceRoute route = ResourceRoute.builder()
                .resourceId(UUID.randomUUID().toString())
                .resourceName("PAYMENT_API")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://api.example.com")
                .build();
        lenient().when(resourceRoutingService.resolveRoute(any(CanonicalAction.class))).thenReturn(Optional.of(route));
        lenient().when(resourceRoutingService.isBindingAuthorized(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private void stubAuditFailure() {
        doThrow(new RuntimeException("audit store down")).when(auditService).record(any());
    }

    @Test
    @DisplayName("EXECUTION_STARTED audit failure prevents connector invocation (fail-closed)")
    void executionStartedAuditFailurePreventsConnector() {
        CanonicalAction action = action(agentIdA);
        when(policyEvaluationService.evaluateDecision(action)).thenReturn(DecisionType.ALLOW);
        stubAuditFailure();
        stubSuccessfulRoute();

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.execute(action));

        assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("EXECUTION_STARTED"));
        verify(connector, never()).execute(any(ConnectorExecutionRequest.class));
    }

    @Test
    @DisplayName("DENY rejection audit failure is preserved as cause on the rejection exception")
    void denyRejectionAuditFailurePreservedAsSuppressed() {
        CanonicalAction action = action(agentIdA);
        when(policyEvaluationService.evaluateDecision(action)).thenReturn(DecisionType.DENY);
        stubAuditFailure();

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.execute(action));

        assertEquals(SystemErrorCode.ERR_RESOURCE_NOT_BOUND, thrown.getErrorCode());
        assertNotNull(thrown.getCause(), "Audit failure must be preserved as cause on rejection");
        assertTrue(thrown.getCause() instanceof NetheriteException, "Cause must be NetheriteException");
        assertTrue(thrown.getCause().getMessage().contains("Failed to record audit event DENY"));
        verify(connector, never()).execute(any());
    }

    @Test
    @DisplayName("Two agents same org same caller idempotency key do not collide (agent-scoped)")
    void differentAgentsSameKeyUseDifferentCompositeKeys() {
        CanonicalAction actionA = action(agentIdA);
        CanonicalAction actionB = action(agentIdB);
        assertEquals(actionA.getIdempotencyKey(), actionB.getIdempotencyKey());

        when(policyEvaluationService.evaluateDecision(actionA)).thenReturn(DecisionType.ALLOW);
        when(policyEvaluationService.evaluateDecision(actionB)).thenReturn(DecisionType.ALLOW);
        when(idempotencyStore.getRecord(anyString(), anyString())).thenReturn(Optional.empty());
        stubSuccessfulRoute();

        service.execute(actionA);
        service.execute(actionB);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore, atLeastOnce()).tryClaim(eq(orgId.toString()), keyCaptor.capture(), any(), any());

        List<String> capturedKeys = keyCaptor.getAllValues();
        boolean foundAgentA = capturedKeys.stream().anyMatch(k -> k.startsWith(agentIdA.toString() + ":"));
        boolean foundAgentB = capturedKeys.stream().anyMatch(k -> k.startsWith(agentIdB.toString() + ":"));
        assertTrue(foundAgentA, "Agent A's composite key must include agentId");
        assertTrue(foundAgentB, "Agent B's composite key must include agentId");
    }

    @Test
    @DisplayName("Same agent same idempotency key uses same composite key (idempotency preserved)")
    void sameAgentSameKeyUsesSameCompositeKey() {
        CanonicalAction actionA = action(agentIdA);
        CanonicalAction actionA2 = action(agentIdA);
        assertEquals(actionA.getAgentId(), actionA2.getAgentId());
        assertEquals(actionA.getIdempotencyKey(), actionA2.getIdempotencyKey());

        when(policyEvaluationService.evaluateDecision(actionA)).thenReturn(DecisionType.ALLOW);
        when(policyEvaluationService.evaluateDecision(actionA2)).thenReturn(DecisionType.ALLOW);
        when(idempotencyStore.getRecord(anyString(), anyString())).thenReturn(Optional.empty());
        stubSuccessfulRoute();

        service.execute(actionA);
        service.execute(actionA2);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore, atLeastOnce()).tryClaim(eq(orgId.toString()), keyCaptor.capture(), any(), any());

        List<String> capturedKeys = keyCaptor.getAllValues();
        boolean allMatchAgentA = capturedKeys.stream()
                .allMatch(k -> k.startsWith(agentIdA.toString() + ":"));
        assertTrue(allMatchAgentA, "All composite keys for same agent must start with agentId:");
    }

    @Test
    @DisplayName("Existing same-agent concurrent claim rejection still works")
    void sameAgentConcurrentClaimRejected() {
        CanonicalAction action = action(agentIdA);
        when(policyEvaluationService.evaluateDecision(action)).thenReturn(DecisionType.ALLOW);
        when(idempotencyStore.getRecord(anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyStore.IdempotencyRecord(
                        "key", IdempotencyStore.IdempotencyState.PROCESSING, null)));
        stubSuccessfulRoute();

        assertThrows(NetheriteException.class, () -> service.execute(action));
        verify(connector, never()).execute(any());
    }
}
