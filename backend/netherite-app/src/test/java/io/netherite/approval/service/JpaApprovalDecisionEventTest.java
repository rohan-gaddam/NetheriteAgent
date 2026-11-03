package io.netherite.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.NotificationService;
import io.netherite.audit.spi.AuditService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused regression tests for approval decision event delivery.
 *
 * <p>Covers: approval and rejection publishing only after commit, no broadcast on
 * failed/rolled-back decisions, minimal payload matching the frontend contract,
 * and cross-tenant isolation (events go only to the ticket's organization).
 */
@ExtendWith(MockitoExtension.class)
class JpaApprovalDecisionEventTest {

    @Mock
    private ApprovalRequestRepository approvalRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ApprovalEventPublisher eventPublisher;

    private JpaApprovalService service;
    private UUID orgId;
    private UUID ticketId;

    @BeforeEach
    void setUp() {
        service = new JpaApprovalService(approvalRepository, auditService, notificationService,
                eventPublisher, new ObjectMapper().registerModule(new JavaTimeModule()));
        orgId = UUID.randomUUID();
        ticketId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private ApprovalRequestEntity pendingEntity() {
        return ApprovalRequestEntity.builder()
                .id(ticketId)
                .organizationId(orgId)
                .agentId(UUID.randomUUID())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .status("PENDING")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private void fireAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    @Test
    @DisplayName("approval publishes APPROVAL_APPROVED only after commit, not before")
    void approvalPublishesAfterCommit() {
        ApprovalRequestEntity entity = pendingEntity();
        when(approvalRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApprovalTicket resolved = service.resolveTicket(ticketId.toString(), ApprovalStatus.APPROVED, "approver-1", null);

        assertEquals(ApprovalStatus.APPROVED, resolved.getStatus());
        // Before commit: no broadcast.
        verify(eventPublisher, never()).publishEvent(any(), any(), any());

        fireAfterCommit();

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publishEvent(eq(orgId.toString()), eq("APPROVAL_APPROVED"), payload.capture());
        assertEquals(ticketId.toString(), payload.getValue().get("ticketId"));
        assertEquals("APPROVED", payload.getValue().get("status"));
        // Minimal payload: no notes, approver identity, or action content.
        assertEquals(2, payload.getValue().size());
    }

    @Test
    @DisplayName("rejection publishes APPROVAL_REJECTED with minimal payload")
    void rejectionPublishesAfterCommit() {
        ApprovalRequestEntity entity = pendingEntity();
        when(approvalRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveTicketWithResult(ticketId.toString(), ApprovalStatus.REJECTED, "approver-2", "not allowed", null, null);
        fireAfterCommit();

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publishEvent(eq(orgId.toString()), eq("APPROVAL_REJECTED"), payload.capture());
        assertEquals(ticketId.toString(), payload.getValue().get("ticketId"));
        assertEquals("REJECTED", payload.getValue().get("status"));
        assertEquals(2, payload.getValue().size());
    }

    @Test
    @DisplayName("failed decision (already resolved) never broadcasts")
    void failedDecisionNeverBroadcasts() {
        ApprovalRequestEntity entity = pendingEntity();
        entity.setStatus("APPROVED");
        when(approvalRepository.findById(ticketId)).thenReturn(Optional.of(entity));

        assertThrows(NetheriteException.class,
                () -> service.resolveTicket(ticketId.toString(), ApprovalStatus.APPROVED, "approver-1", null));

        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verify(eventPublisher, never()).publishEvent(any(), any(), any());
    }

    @Test
    @DisplayName("rollback (commit never fires) never broadcasts")
    void rollbackNeverBroadcasts() {
        ApprovalRequestEntity entity = pendingEntity();
        when(approvalRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveTicket(ticketId.toString(), ApprovalStatus.APPROVED, "approver-1", null);

        // Simulate rollback: synchronization discarded without afterCommit.
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();

        verify(eventPublisher, never()).publishEvent(any(), any(), any());
    }

    @Test
    @DisplayName("decision event goes only to the ticket's organization")
    void crossTenantIsolation() {
        UUID otherOrg = UUID.randomUUID();
        ApprovalRequestEntity entity = pendingEntity();
        when(approvalRepository.findById(ticketId)).thenReturn(Optional.of(entity));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveTicket(ticketId.toString(), ApprovalStatus.APPROVED, "approver-1", null);
        fireAfterCommit();

        verify(eventPublisher).publishEvent(eq(orgId.toString()), eq("APPROVAL_APPROVED"), any());
        verify(eventPublisher, never()).publishEvent(eq(otherOrg.toString()), any(), any());
    }
}
