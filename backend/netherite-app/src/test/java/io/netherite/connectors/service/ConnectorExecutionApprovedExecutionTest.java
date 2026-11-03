package io.netherite.connectors.service;

import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.service.ApprovalEventPublisher;
import io.netherite.approval.spi.ApprovalService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BLOCK-2 regression: {@code executeApproved} must run an action a human already approved while
 * (a) never raising a duplicate approval ticket, (b) still honouring a fresh DENY, and (c) failing
 * closed on any ticket that does not authorize exactly this action/organization.
 */
@ExtendWith(MockitoExtension.class)
class ConnectorExecutionApprovedExecutionTest {

    @Mock private PolicyEvaluationService policyEvaluationService;
    @Mock private ResourceRoutingService resourceRoutingService;
    @Mock private SsrfGuardValidator ssrfGuardValidator;
    @Mock private IdempotencyStore idempotencyStore;
    @Mock private DistributedLockManager lockManager;
    @Mock private ApprovalService approvalService;
    @Mock private ApprovalEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private DataRedactionService dataRedactionService;
    @Mock private HealthCheckService healthCheckService;
    @Mock private NetheriteDistributedProperties distributedProperties;

    private final ResourceConnector connector = mock(ResourceConnector.class);

    private ConnectorExecutionService service;
    private UUID orgId;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        service = new ConnectorExecutionService(
                policyEvaluationService, resourceRoutingService, ssrfGuardValidator,
                List.of(connector), idempotencyStore, lockManager, approvalService,
                eventPublisher, auditService, dataRedactionService, healthCheckService,
                distributedProperties);
        orgId = UUID.randomUUID();
        agentId = UUID.randomUUID();
    }

    private CanonicalAction action() {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(orgId.toString())
                .agentId(agentId.toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of("amount", 100))
                .timestamp(Instant.now())
                .build();
    }

    private ApprovalTicket ticketFor(CanonicalAction action, ApprovalStatus status) {
        return ApprovalTicket.builder()
                .ticketId(UUID.randomUUID().toString())
                .organizationId(action.getOrganizationId())
                .agentId(action.getAgentId())
                .status(status)
                .action(action)
                .build();
    }

    private void stubSuccessfulRoute() {
        NetheriteDistributedProperties.Lock lockProperties = mock(NetheriteDistributedProperties.Lock.class);
        when(lockProperties.getExecutionWaitMillis()).thenReturn(1_000L);
        when(lockProperties.getExecutionLeaseMillis()).thenReturn(1_000L);
        when(distributedProperties.getLock()).thenReturn(lockProperties);
        ResourceRoute route = ResourceRoute.builder()
                .resourceId(UUID.randomUUID().toString())
                .resourceName("PAYMENT_API")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://api.example.com")
                .build();
        when(resourceRoutingService.resolveRoute(any(CanonicalAction.class))).thenReturn(Optional.of(route));
        when(resourceRoutingService.isBindingAuthorized(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(ssrfGuardValidator.isUriPermitted(any())).thenReturn(true);
        when(lockManager.tryAcquireLock(anyString(), any(), any()))
                .thenReturn(Optional.of(mock(DistributedLock.class)));
        when(connector.getConnectorType()).thenReturn(ConnectorType.HTTP_REST);
        when(connector.execute(any(ConnectorExecutionRequest.class)))
                .thenReturn(ConnectorExecutionResult.builder().successful(true).statusCode(200).build());
    }

    @Test
    @DisplayName("BLOCK-2. an approved HUMAN_APPROVAL action executes and creates no duplicate ticket")
    void approvedHumanApprovalExecutesWithoutDuplicateTicket() {
        CanonicalAction action = action();
        ApprovalTicket ticket = ticketFor(action, ApprovalStatus.APPROVED);
        when(approvalService.getTicket(ticket.getTicketId())).thenReturn(Optional.of(ticket));
        when(policyEvaluationService.evaluateDecision(action)).thenReturn(DecisionType.HUMAN_APPROVAL);
        stubSuccessfulRoute();

        ConnectorExecutionResult result = service.executeApproved(action, ticket.getTicketId());

        assertTrue(result.isSuccessful());
        verify(connector).execute(any(ConnectorExecutionRequest.class));
        verify(approvalService, never()).createTicket(any(), any());
    }

    @Test
    @DisplayName("BLOCK-2. a DENY decision still blocks execution of an approved action")
    void denyBlocksEvenWithApprovedTicket() {
        CanonicalAction action = action();
        ApprovalTicket ticket = ticketFor(action, ApprovalStatus.APPROVED);
        when(approvalService.getTicket(ticket.getTicketId())).thenReturn(Optional.of(ticket));
        when(policyEvaluationService.evaluateDecision(action)).thenReturn(DecisionType.DENY);

        assertThrows(NetheriteException.class, () -> service.executeApproved(action, ticket.getTicketId()));

        verify(connector, never()).execute(any());
        verify(approvalService, never()).createTicket(any(), any());
    }

    @Test
    @DisplayName("BLOCK-2. a ticket from another organization is rejected")
    void wrongOrganizationRejected() {
        CanonicalAction action = action();
        CanonicalAction otherOrgAction = action();
        otherOrgAction.setOrganizationId(UUID.randomUUID().toString());
        ApprovalTicket ticket = ticketFor(otherOrgAction, ApprovalStatus.APPROVED);
        when(approvalService.getTicket(ticket.getTicketId())).thenReturn(Optional.of(ticket));

        assertThrows(NetheriteException.class, () -> service.executeApproved(action, ticket.getTicketId()));

        verify(policyEvaluationService, never()).evaluateDecision(any());
        verify(connector, never()).execute(any());
    }

    @Test
    @DisplayName("BLOCK-2. a non-approved (PENDING) ticket is rejected")
    void nonApprovedTicketRejected() {
        CanonicalAction action = action();
        ApprovalTicket ticket = ticketFor(action, ApprovalStatus.PENDING);
        when(approvalService.getTicket(ticket.getTicketId())).thenReturn(Optional.of(ticket));

        assertThrows(NetheriteException.class, () -> service.executeApproved(action, ticket.getTicketId()));

        verify(policyEvaluationService, never()).evaluateDecision(any());
        verify(connector, never()).execute(any());
    }

    @Test
    @DisplayName("BLOCK-2. an approved action that differs from the ticket's action is rejected")
    void actionMismatchRejected() {
        CanonicalAction action = action();
        ApprovalTicket ticket = ticketFor(action(), ApprovalStatus.APPROVED);
        when(approvalService.getTicket(ticket.getTicketId())).thenReturn(Optional.of(ticket));

        assertThrows(NetheriteException.class, () -> service.executeApproved(action, ticket.getTicketId()));

        verify(policyEvaluationService, never()).evaluateDecision(any());
        verify(connector, never()).execute(any());
    }

    @Test
    @DisplayName("BLOCK-2. a missing ticket is rejected")
    void missingTicketRejected() {
        CanonicalAction action = action();
        when(approvalService.getTicket("missing")).thenReturn(Optional.empty());

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.executeApproved(action, "missing"));

        assertEquals(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, thrown.getErrorCode());
        verify(connector, never()).execute(any());
    }

    @Test
    @DisplayName("BLOCK-2. without an approval the HUMAN_APPROVAL gate still raises a ticket (no bypass)")
    void withoutApprovalStillRaisesTicket() {
        CanonicalAction action = action();
        when(policyEvaluationService.evaluateDecision(action)).thenReturn(DecisionType.HUMAN_APPROVAL);
        when(approvalService.createTicket(any(), any())).thenReturn(ticketFor(action, ApprovalStatus.PENDING));

        assertThrows(NetheriteException.class, () -> service.execute(action));

        verify(approvalService).createTicket(any(), any());
        verify(connector, never()).execute(any());
    }
}
