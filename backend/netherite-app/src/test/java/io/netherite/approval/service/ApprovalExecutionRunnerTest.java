package io.netherite.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netherite.common.model.CanonicalAction;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.connectors.service.ConnectorExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for approval-continuation resource resolution and execution safety.
 *
 * <p>Root cause covered: {@code CanonicalAction.getTargetResource()} is the resource
 * NAME (gateway tickets store e.g. {@code "SSELIVE_HIGH_API"}), which the runner used
 * to parse as a UUID directly — failing every gateway-created continuation with an
 * unhandled async error (NPE for missing values). The runner now resolves through the
 * same trusted, tenant-scoped routing as the initial execution.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalExecutionRunnerTest {

    @Mock
    private ApprovalRequestRepository approvalRequestRepository;

    @Mock
    private AgentDomainService agentDomainService;

    @Mock
    private ResourceDomainService resourceDomainService;

    @Mock
    private ResourceRoutingService resourceRoutingService;

    @Mock
    private ApprovalEventPublisher eventPublisher;

    @Mock
    private ConnectorExecutionService connectorExecutionService;

    private ApprovalExecutionRunner runner;
    private UUID orgId;
    private UUID ticketId;
    private UUID agentId;
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        runner = new ApprovalExecutionRunner(approvalRequestRepository, agentDomainService,
                resourceDomainService, resourceRoutingService, eventPublisher, connectorExecutionService,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        orgId = UUID.randomUUID();
        ticketId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        resourceId = UUID.randomUUID();
    }

    private void stubActiveAgent() {
        AgentEntity activeAgent = AgentEntity.builder().status("ACTIVE").build();
        when(agentDomainService.getAgent(orgId, agentId)).thenReturn(activeAgent);
    }

    private ApprovalRequestEntity approvedEntity(Object targetResource) {
        Map<String, Object> canonicalAction = new HashMap<>();
        canonicalAction.put("targetResource", targetResource);
        canonicalAction.put("actionType", "PAYMENT:REFUND");
        return ApprovalRequestEntity.builder()
                .id(ticketId)
                .organizationId(orgId)
                .agentId(agentId)
                .actionType("PAYMENT:REFUND")
                .targetResource("SSELIVE_HIGH_API")
                .canonicalAction(canonicalAction)
                .status("APPROVED")
                .build();
    }

    private void stubResolvableRoute() {
        ResourceRoute route = ResourceRoute.builder()
                .resourceId(resourceId.toString())
                .resourceName("SSELIVE_HIGH_API")
                .build();
        when(resourceRoutingService.resolveRoute(any(CanonicalAction.class)))
                .thenReturn(Optional.of(route));
        ResourceEntity resource = ResourceEntity.builder().status("ACTIVE").build();
        when(resourceDomainService.getResource(orgId, resourceId)).thenReturn(resource);
        when(resourceRoutingService.isBindingAuthorized(
                eq(orgId.toString()), eq(agentId.toString()), eq(resourceId.toString()), eq("PAYMENT:REFUND")))
                .thenReturn(true);
    }

    @Test
    @DisplayName("resource name resolves through routing and executes the intended action")
    void resourceNameResolvesAndExecutesIntendedAction() throws Exception {
        stubActiveAgent();
        ApprovalRequestEntity entity = approvedEntity("SSELIVE_HIGH_API");
        when(approvalRequestRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        stubResolvableRoute();
        ConnectorExecutionResult result = ConnectorExecutionResult.builder()
                .successful(true).statusCode(200).build();
        when(connectorExecutionService.executeApproved(any(CanonicalAction.class), any())).thenReturn(result);
        when(approvalRequestRepository.completeExecution(eq(ticketId), eq("node-1"), any())).thenReturn(1);

        assertDoesNotThrow(() -> runner.execute(ticketId.toString(), "node-1"));

        ArgumentCaptor<CanonicalAction> action = ArgumentCaptor.forClass(CanonicalAction.class);
        verify(connectorExecutionService).executeApproved(action.capture(), eq(ticketId.toString()));
        assertEquals("PAYMENT:REFUND", action.getValue().getActionType());
        ArgumentCaptor<Map> event = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publishEvent(eq(orgId.toString()), eq("APPROVAL_EXECUTED"), event.capture());
        assertEquals(ticketId.toString(), event.getValue().get("ticketId"));
        assertEquals("COMPLETED", event.getValue().get("status"));
    }

    @Test
    @DisplayName("unresolvable route fails deterministically instead of throwing")
    void unresolvableRouteFailsDeterministically() {
        stubActiveAgent();
        ApprovalRequestEntity entity = approvedEntity(null);
        when(approvalRequestRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        when(resourceRoutingService.resolveRoute(any(CanonicalAction.class))).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> runner.execute(ticketId.toString(), "node-1"));

        verify(approvalRequestRepository).failExecution(eq(ticketId), eq("node-1"), any());
        verify(eventPublisher).publishEvent(eq(orgId.toString()),
                eq("APPROVAL_CONTINUATION_FAILED"), any());
        verify(approvalRequestRepository, never()).completeExecution(any(), any(), any());
        verify(connectorExecutionService, never()).executeApproved(any(), any());
    }

    @Test
    @DisplayName("rejected ticket never executes")
    void rejectedTicketNeverExecutes() {
        ApprovalRequestEntity entity = approvedEntity("SSELIVE_HIGH_API");
        entity.setStatus("REJECTED");
        when(approvalRequestRepository.findById(ticketId)).thenReturn(Optional.of(entity));

        assertDoesNotThrow(() -> runner.execute(ticketId.toString(), "node-1"));

        verify(connectorExecutionService, never()).executeApproved(any(), any());
        verify(eventPublisher, never()).publishEvent(any(), any(), any());
        verify(approvalRequestRepository, never()).failExecution(any(), any(), any());
        verify(approvalRequestRepository, never()).completeExecution(any(), any(), any());
    }

    @Test
    @DisplayName("lost execution claim cannot publish a second completion")
    void lostClaimCannotDoubleExecute() throws Exception {
        stubActiveAgent();
        ApprovalRequestEntity entity = approvedEntity("SSELIVE_HIGH_API");
        when(approvalRequestRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        stubResolvableRoute();
        ConnectorExecutionResult result = ConnectorExecutionResult.builder()
                .successful(true).statusCode(200).build();
        when(connectorExecutionService.executeApproved(any(CanonicalAction.class), any())).thenReturn(result);
        when(approvalRequestRepository.completeExecution(eq(ticketId), eq("node-1"), any())).thenReturn(0);

        assertDoesNotThrow(() -> runner.execute(ticketId.toString(), "node-1"));

        verify(eventPublisher, never()).publishEvent(eq(orgId.toString()), eq("APPROVAL_EXECUTED"), any());
    }

    @Test
    @DisplayName("revoked binding fails closed without executing")
    void revokedBindingFailsClosed() {
        stubActiveAgent();
        ApprovalRequestEntity entity = approvedEntity("SSELIVE_HIGH_API");
        when(approvalRequestRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        ResourceRoute route = ResourceRoute.builder()
                .resourceId(resourceId.toString())
                .resourceName("SSELIVE_HIGH_API")
                .build();
        when(resourceRoutingService.resolveRoute(any(CanonicalAction.class)))
                .thenReturn(Optional.of(route));
        ResourceEntity resource = ResourceEntity.builder().status("ACTIVE").build();
        when(resourceDomainService.getResource(orgId, resourceId)).thenReturn(resource);
        when(resourceRoutingService.isBindingAuthorized(any(), any(), any(), any())).thenReturn(false);

        assertDoesNotThrow(() -> runner.execute(ticketId.toString(), "node-1"));

        verify(connectorExecutionService, never()).executeApproved(any(), any());
        verify(eventPublisher).publishEvent(eq(orgId.toString()),
                eq("APPROVAL_CONTINUATION_FAILED"), any());
    }

    @Test
    @DisplayName("invalid ticket id is skipped without repository access")
    void invalidTicketIdSkipped() {
        assertDoesNotThrow(() -> runner.execute("not-a-uuid", "node-1"));

        verify(approvalRequestRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(), any(), any());
    }
}
