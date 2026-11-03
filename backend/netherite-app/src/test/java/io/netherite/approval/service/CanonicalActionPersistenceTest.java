package io.netherite.approval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.common.model.CanonicalAction;
import io.netherite.context.model.DecisionContext;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BLOCK-1 regression: {@code JpaApprovalService.createTicket} previously serialised the
 * {@link CanonicalAction} with a bare {@code new ObjectMapper()}. Serialising the required
 * {@code Instant timestamp} threw, the throw was swallowed, and the ticket's JSON column was
 * persisted EMPTY. This suite proves - against the real Spring mapper and the real database -
 * that a created ticket carries a complete, executable canonical action and that the timestamp
 * survives the full persistence round trip.
 */
@SpringBootTest
@ActiveProfiles("test")
class CanonicalActionPersistenceTest {

    @Autowired private ApprovalService approvalService;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        orgId = organizationService.createOrganization(
                "Canonical Persistence Corp", "cap-" + UUID.randomUUID().toString().substring(0, 8)).getId();
        agentId = agentService.registerAgent(
                orgId, "CanonicalPersistenceAgent", "BLOCK-1 agent", "TIER_3").agent().getId();
    }

    @Test
    @DisplayName("BLOCK-1. createTicket persists a complete canonical action and round-trips the Instant")
    void createTicketPersistsCompleteCanonicalActionAndRoundTripsInstant() {
        Instant timestamp = Instant.parse("2026-01-02T03:04:05.123456Z");
        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(orgId.toString())
                .agentId(agentId.toString())
                .userId("user-1")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of("amount", 100, "currency", "USD"))
                .timestamp(timestamp)
                .build();
        DecisionContext context = DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of("risk", "low"))
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));
        assertNotNull(ticket.getTicketId());

        ApprovalRequestEntity entity = approvalRequestRepository
                .findById(UUID.fromString(ticket.getTicketId())).orElseThrow();

        Map<String, Object> stored = entity.getCanonicalAction();
        assertNotNull(stored, "canonical action must be stored");
        assertFalse(stored.isEmpty(), "canonical action must not be persisted empty");
        assertTrue(stored.keySet().containsAll(List.of(
                        "actionId", "organizationId", "agentId", "actionType",
                        "targetResource", "timestamp", "parameters")),
                "stored canonical action must retain every required field, was: " + stored.keySet());
        assertFalse(entity.getDecisionContext().isEmpty(), "decision context must not be persisted empty");

        CanonicalAction roundTripped = objectMapper.convertValue(stored, CanonicalAction.class);
        assertEquals(timestamp, roundTripped.getTimestamp(), "Instant timestamp must survive persistence");
        assertEquals(action.getActionId(), roundTripped.getActionId());
        assertEquals(action.getParameters(), roundTripped.getParameters());

        // The read path used by the approval continuation must expose the full action.
        ApprovalTicket reread = approvalService.getTicket(ticket.getTicketId()).orElseThrow();
        assertNotNull(reread.getAction(), "ticket must expose its canonical action");
        assertEquals(timestamp, reread.getAction().getTimestamp());
        assertEquals("PAYMENT:REFUND", reread.getAction().getActionType());
    }
}
