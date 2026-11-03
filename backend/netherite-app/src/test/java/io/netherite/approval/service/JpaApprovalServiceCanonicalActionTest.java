package io.netherite.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netherite.approval.spi.NotificationService;
import io.netherite.audit.spi.AuditService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.context.model.DecisionContext;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BLOCK-1 regression: the silent {@code Map.of()} fallback must be gone. A failure to serialize the
 * canonical action - or an action missing a required field - must fail closed with a surfaced error
 * and must NOT persist a ticket whose action cannot later be reconstructed.
 */
@ExtendWith(MockitoExtension.class)
class JpaApprovalServiceCanonicalActionTest {

    @Mock private ApprovalRequestRepository approvalRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private ApprovalEventPublisher eventPublisher;

    private UUID orgId;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        agentId = UUID.randomUUID();
    }

    private JpaApprovalService serviceWith(ObjectMapper mapper) {
        return new JpaApprovalService(approvalRepository, auditService, notificationService,
                eventPublisher, mapper);
    }

    private CanonicalAction action(String targetResource, Map<String, Object> parameters) {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(orgId.toString())
                .agentId(agentId.toString())
                .actionType("PAYMENT:REFUND")
                .targetResource(targetResource)
                .parameters(parameters)
                .timestamp(Instant.now())
                .build();
    }

    private DecisionContext context(CanonicalAction action) {
        return DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("BLOCK-1. a serialization failure is surfaced and no ticket is persisted")
    void serializationFailureIsSurfacedAndNothingPersisted() {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.convertValue(any(), any(Class.class))).thenThrow(new IllegalStateException("mapper boom"));
        JpaApprovalService service = serviceWith(failing);

        NetheriteException thrown = assertThrows(NetheriteException.class, () -> service.createTicket(
                context(action("PAYMENT_API", Map.of("amount", 1))), List.of("base-policy")));

        assertEquals(SystemErrorCode.ERR_CANONICALIZATION_FAILED, thrown.getErrorCode());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    @DisplayName("BLOCK-1. an action missing a required field is rejected and no ticket is persisted")
    void incompleteActionIsRejectedAndNothingPersisted() {
        JpaApprovalService service = serviceWith(new ObjectMapper().registerModule(new JavaTimeModule()));

        NetheriteException thrown = assertThrows(NetheriteException.class, () -> service.createTicket(
                context(action(null, Map.of("amount", 1))), List.of("base-policy")));

        assertEquals(SystemErrorCode.ERR_CANONICALIZATION_FAILED, thrown.getErrorCode());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    @DisplayName("BLOCK-1. a complete action is persisted with a populated canonical action")
    void completeActionIsPersistedPopulated() {
        JpaApprovalService service = serviceWith(new ObjectMapper().registerModule(new JavaTimeModule()));
        when(approvalRepository.save(any())).thenAnswer(invocation -> {
            ApprovalRequestEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });

        service.createTicket(context(action("PAYMENT_API", Map.of("amount", 1, "currency", "USD"))),
                List.of("base-policy"));

        ArgumentCaptor<ApprovalRequestEntity> captor = ArgumentCaptor.forClass(ApprovalRequestEntity.class);
        verify(approvalRepository).save(captor.capture());
        Map<String, Object> stored = captor.getValue().getCanonicalAction();
        assertNotNull(stored);
        assertFalse(stored.isEmpty(), "canonical action must not be persisted empty");
        assertTrue(stored.keySet().containsAll(List.of(
                "actionId", "organizationId", "agentId", "actionType",
                "targetResource", "timestamp", "parameters")));
    }
}
