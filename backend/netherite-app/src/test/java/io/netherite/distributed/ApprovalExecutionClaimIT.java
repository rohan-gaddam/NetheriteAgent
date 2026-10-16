package io.netherite.distributed;

import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WORKSTREAM 3 - Approval execution claims against REAL PostgreSQL. Exercises the
 * repository claim protocol exactly as the distributed recovery machinery uses it:
 * exclusive cross-node claim, owner-scoped release/complete/fail, expired-lease
 * takeover, and the retry vs exhausted recovery sweep.
 */
@SpringBootTest
@ActiveProfiles("distributed")
class ApprovalExecutionClaimIT {

    @Autowired private ApprovalRequestRepository repository;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private DataSource dataSource;

    private UUID orgId;
    private UUID agentId;

    @AfterEach
    void tearDown() {
        // Orgs are randomly slugged per run; rows accumulate in the distributed DB like other IT suites.
    }

    private ApprovalRequestEntity setUpOrgAndAgent() {
        var org = organizationService.createOrganization(
                "Approval Claim Corp", "aclaim-" + UUID.randomUUID().toString().substring(0, 8));
        orgId = org.getId();
        var agent = agentService.registerAgent(orgId, "AClaimAgent", "Approval Claim Agent", "TIER_1");
        agentId = agent.agent().getId();
        return null;
    }

    private ApprovalRequestEntity approvedTicket() {
        if (orgId == null) {
            setUpOrgAndAgent();
        }
        ApprovalRequestEntity entity = ApprovalRequestEntity.builder()
                .organizationId(orgId)
                .agentId(agentId)
                .userId("u-" + UUID.randomUUID())
                .actionType("PAYMENT:REFUND")
                .targetResource("resource-x")
                .canonicalAction(Map.of("actionType", "PAYMENT:REFUND", "targetResource", "resource-x"))
                .decisionContext(Map.of("risk", "low"))
                .triggeredPolicies(List.of("base-policy"))
                .status("APPROVED")
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .build();
        return repository.saveAndFlush(entity);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private void forceLeaseExpired(UUID id) {
        jdbc().update("UPDATE approval_requests SET execution_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes' WHERE id = ?", id);
    }

    private int attemptsOf(UUID id) {
        Integer a = jdbc().queryForObject(
                "SELECT execution_attempts FROM approval_requests WHERE id = ?", Integer.class, id);
        return a == null ? -1 : a;
    }

    @Test
    @DisplayName("Exclusive claim: one node wins, the other is denied until completion frees the ticket")
    void exclusiveClaimAndCompletion() {
        ApprovalRequestEntity ticket = approvedTicket();

        assertEquals(1, repository.claimExecution(ticket.getId(), "node-a",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));
        assertEquals(0, repository.claimExecution(ticket.getId(), "node-b",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))),
                "A second node must be denied while node-a holds the lease");

        assertEquals(1, repository.completeExecution(ticket.getId(), "node-a", "{\"ok\":true}"));

        ApprovalRequestEntity stored = repository.findById(ticket.getId()).orElseThrow();
        assertNotNull(stored.getExecutionResult());
        assertFalse(stored.getExecutionInProgress());
        assertNull(stored.getExecutionClaimedBy());

        assertEquals(0, repository.claimExecution(ticket.getId(), "node-b",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))),
                "Completed tickets must never be re-claimed");
    }

    @Test
    @DisplayName("A non-owner cannot release, complete, or re-claim a held ticket")
    void wrongOwnerCannotTouchClaim() {
        ApprovalRequestEntity ticket = approvedTicket();

        assertEquals(1, repository.claimExecution(ticket.getId(), "node-a",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));

        assertEquals(0, repository.releaseExecutionClaim(ticket.getId(), "node-b"));
        assertEquals(0, repository.completeExecution(ticket.getId(), "node-b", "{}"));
        assertEquals(0, repository.failExecution(ticket.getId(), "node-b", "err"));

        assertEquals(0, repository.claimExecution(ticket.getId(), "node-b",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));

        assertEquals(1, repository.releaseExecutionClaim(ticket.getId(), "node-a"));
        assertEquals(1, repository.claimExecution(ticket.getId(), "node-b",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))),
                "After the owner releases, another node may claim");
    }

    @Test
    @DisplayName("Expired lease (crashed holder) is taken over by another node with an attempt bump")
    void expiredLeaseAllowsTakeover() {
        ApprovalRequestEntity ticket = approvedTicket();

        Instant past = Instant.now().minus(Duration.ofMinutes(20));
        assertEquals(1, repository.claimExecution(ticket.getId(), "node-a",
                past, past.plus(Duration.ofMinutes(15))));
        assertEquals(1, attemptsOf(ticket.getId()));

        assertEquals(1, repository.claimExecution(ticket.getId(), "node-b",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))),
                "An expired lease (crashed node) must be taken over");
        assertEquals(2, attemptsOf(ticket.getId()),
                "Takeover of a live-but-stale claim must bump the attempt count");

        ApprovalRequestEntity stored = repository.findById(ticket.getId()).orElseThrow();
        assertEquals("node-b", stored.getExecutionClaimedBy());
        assertEquals(2, stored.getExecutionAttempts());
        assertTrue(stored.getExecutionInProgress());
    }

    @Test
    @DisplayName("Recovery sweep re-runs stale claims below the attempt bound until they complete")
    void recoverySweepReclaimsBelowMaxAttempts() {
        ApprovalRequestEntity ticket = approvedTicket();

        assertEquals(1, repository.claimExecution(ticket.getId(), "node-a",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));
        forceLeaseExpired(ticket.getId());

        List<ApprovalRequestEntity> stale = repository.findStaleExecutions(Instant.now(), 3);
        assertTrue(stale.stream().anyMatch(t -> t.getId().equals(ticket.getId())),
                "Stale claim below the attempt bound must be picked up for re-execution");
        assertTrue(repository.findExhaustedExecutions(Instant.now(), 3).stream()
                        .noneMatch(t -> t.getId().equals(ticket.getId())));

        assertEquals(1, repository.claimExecution(ticket.getId(), "node-b",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));
        assertEquals(1, repository.completeExecution(ticket.getId(), "node-b", "{\"ok\":true}"));

        ApprovalRequestEntity stored = repository.findById(ticket.getId()).orElseThrow();
        assertEquals(2, stored.getExecutionAttempts());
        assertNotNull(stored.getExecutionResult());
    }

    @Test
    @DisplayName("A poisoned ticket exhausting the attempt bound becomes terminal via the sweep")
    void exhaustedAttemptsBecomeTerminal() {
        ApprovalRequestEntity ticket = approvedTicket();
        int max = 3;

        for (int i = 1; i <= max; i++) {
            assertEquals(1, repository.claimExecution(ticket.getId(), "node-" + i,
                    Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));
            forceLeaseExpired(ticket.getId());
        }
        assertEquals(max, attemptsOf(ticket.getId()));

        assertTrue(repository.findStaleExecutions(Instant.now(), max).stream()
                        .noneMatch(t -> t.getId().equals(ticket.getId())),
                "Once attempts reach the bound the ticket is no longer retryable");
        assertTrue(repository.findExhaustedExecutions(Instant.now(), max).stream()
                        .anyMatch(t -> t.getId().equals(ticket.getId())));

        String error = "Approval execution exceeded maximum retry attempts";
        assertEquals(1, repository.exhaustExecution(ticket.getId(), Instant.now(), error));

        ApprovalRequestEntity stored = repository.findById(ticket.getId()).orElseThrow();
        assertEquals(error, stored.getExecutionError());
        assertEquals(max, stored.getExecutionAttempts());
        assertFalse(stored.getExecutionInProgress());
        assertNull(stored.getExecutionClaimedBy());
    }

    @Test
    @DisplayName("Terminal (non-APPROVED) tickets can never be claimed")
    void nonApprovedTicketsUnclaimable() {
        ApprovalRequestEntity ticket = approvedTicket();
        ticket.setStatus("REJECTED");
        ticket.setApproverUserIdentifier("u-approver");
        ticket.setResolvedAt(Instant.now());
        repository.saveAndFlush(ticket);

        assertEquals(0, repository.claimExecution(ticket.getId(), "node-a",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));
        assertEquals(0, attemptsOf(ticket.getId()), "Denied claims must not increment the attempt counter");
    }
}