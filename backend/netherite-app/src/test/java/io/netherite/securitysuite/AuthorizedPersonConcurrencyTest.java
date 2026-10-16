package io.netherite.securitysuite;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.OrganizationOwnershipService;
import io.netherite.testsupport.DesignatedPersonFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * WS6 — the ownership lifecycle under real concurrency.
 *
 * <p>The single-column designation is only safe if a race can never leave the
 * organization in a state neither request intended. {@link
 * OrganizationOwnershipService} serializes every mutation on the organization
 * row with {@code SELECT ... FOR UPDATE} and re-reads the holder under that
 * lock; these tests exercise that claim directly rather than inferring it from
 * sequential calls, which would pass even without the lock.</p>
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: a rolled-back test
 * transaction would be invisible to the second thread, so the race would be
 * simulated rather than real. Fixtures are committed and removed explicitly
 * afterwards instead.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthorizedPersonConcurrencyTest {

    @Autowired private OrganizationOwnershipService ownershipService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private DesignatedPersonFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
    }

    @AfterEach
    void tearDown() {
        // Audit rows are append-only at the ORM level, so they are removed
        // directly rather than through their repository.
        for (UUID organizationId : fixture.createdOrganizationIds()) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE organization_id = ?", organizationId);
            jdbcTemplate.update("DELETE FROM audit_chain_heads WHERE organization_id = ?", organizationId);
        }
        fixture.deleteCreated();
    }

    @Test
    @DisplayName("CONC-1. Two simultaneous transfers produce exactly one winner")
    void concurrentTransfersProduceExactlyOneWinner() throws Exception {
        OrganizationEntity org = fixture.newOrganization("Race");
        UserEntity holder = fixture.newMember(org, "holder");
        UserEntity first = fixture.newMember(org, "first");
        UserEntity second = fixture.newMember(org, "second");
        fixture.designate(org, holder);

        UUID organizationId = org.getId();
        UUID holderId = holder.getId();
        UUID firstId = first.getId();
        UUID secondId = second.getId();

        List<Outcome> outcomes = runConcurrently(
                () -> transfer(organizationId, firstId, holderId, holderId),
                () -> transfer(organizationId, secondId, holderId, holderId));

        List<Outcome> winners = outcomes.stream().filter(Outcome::succeeded).toList();
        List<Outcome> losers = outcomes.stream().filter(o -> !o.succeeded()).toList();
        assertEquals(1, winners.size(),
                "exactly one transfer must win the race, outcomes were " + describe(outcomes));
        assertEquals(1, losers.size(), "exactly one transfer must lose the race");

        Outcome winner = winners.get(0);
        Outcome loser = losers.get(0);
        assertEquals(SystemErrorCode.ERR_CONFLICT, loser.error(),
                "the loser must be rejected because its expectation went stale under the lock, not "
                        + "because of an authorization or validation failure");
        assertTrue(winner.value().equals(firstId) || winner.value().equals(secondId),
                "the winner must be one of the two attempted targets, was " + winner.value());
        assertEquals(winner.value(), fixture.currentDesignation(org),
                "the committed designation must be the winner's target");
    }

    @Test
    @DisplayName("CONC-2. Two simultaneous bootstraps produce exactly one designation")
    void concurrentBootstrapsProduceExactlyOneDesignation() throws Exception {
        OrganizationEntity org = fixture.newOrganization("Contested");
        UserEntity claimantA = fixture.newMember(org, "claimant-a");
        UserEntity claimantB = fixture.newMember(org, "claimant-b");
        assertNull(fixture.currentDesignation(org), "a fresh organization starts undesignated");

        UUID organizationId = org.getId();
        UUID claimantAId = claimantA.getId();
        UUID claimantBId = claimantB.getId();

        List<Outcome> outcomes = runConcurrently(
                () -> bootstrap(organizationId, claimantAId, claimantAId),
                () -> bootstrap(organizationId, claimantBId, claimantBId));

        List<Outcome> granted = outcomes.stream().filter(Outcome::succeeded).toList();
        List<Outcome> refused = outcomes.stream().filter(o -> !o.succeeded()).toList();

        assertEquals(1, granted.size(), "bootstrap must not be claimable twice");
        assertEquals(1, refused.size(), "the second claimant must be refused");
        assertEquals(SystemErrorCode.ERR_FORBIDDEN, refused.get(0).error(),
                "claiming an organization that already has a person is an authorization refusal");
        assertEquals(granted.get(0).value(), fixture.currentDesignation(org),
                "the surviving designation must belong to the successful claimant");
    }

    @Test
    @DisplayName("CONC-3. A transfer that loses the race cannot be replayed with the old expectation")
    void staleExpectationIsRejectedAfterTheRace() throws Exception {
        OrganizationEntity org = fixture.newOrganization("Stale");
        UserEntity holder = fixture.newMember(org, "holder");
        UserEntity candidateA = fixture.newMember(org, "candidate-a");
        UserEntity candidateB = fixture.newMember(org, "candidate-b");
        fixture.designate(org, holder);

        UUID organizationId = org.getId();
        UUID holderId = holder.getId();
        UUID candidateAId = candidateA.getId();
        UUID candidateBId = candidateB.getId();

        List<Outcome> outcomes = runConcurrently(
                () -> transfer(organizationId, candidateAId, holderId, holderId),
                () -> transfer(organizationId, candidateBId, holderId, holderId));

        List<Outcome> winners = outcomes.stream().filter(Outcome::succeeded).toList();
        assertEquals(1, winners.size(),
                "exactly one transfer must win the race, outcomes were " + describe(outcomes));
        UUID winner = winners.get(0).value();
        UUID other = winner.equals(candidateAId) ? candidateBId : candidateAId;
        assertEquals(winner, fixture.currentDesignation(org));

        // Replay of the losing request: the caller still believes 'holder' is in
        // charge, so the expectation is stale and the write must not apply.
        try {
            transfer(organizationId, other, holderId, holderId);
            fail("a stale expectation must not transfer ownership");
        } catch (NetheriteException e) {
            assertEquals(SystemErrorCode.ERR_CONFLICT, e.getErrorCode());
        }
        assertEquals(winner, fixture.currentDesignation(org),
                "the replayed request must leave the real holder in place");
    }

    // ======================= helpers =======================

    /** @return the resulting designation. */
    private UUID transfer(UUID organizationId, UUID newUserId, UUID actorUserId, UUID expectedCurrentUserId) {
        return ownershipService.transferAuthorizedPerson(
                        organizationId, newUserId, actorUserId, expectedCurrentUserId, "concurrency-test")
                .getAuthorizedPersonUserId();
    }

    /** @return the resulting designation. */
    private UUID bootstrap(UUID organizationId, UUID userId, UUID actorUserId) {
        return ownershipService.bootstrapAuthorizedPerson(
                        organizationId, userId, actorUserId, "concurrency-test")
                .getAuthorizedPersonUserId();
    }

    /**
     * Runs both callables at the same time, releasing them together on a barrier
     * so the overlap is real rather than scheduling luck, and returns both
     * outcomes in submission order.
     */
    private List<Outcome> runConcurrently(ConcurrentCall first, ConcurrentCall second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> invoke(first, barrier));
            Future<Outcome> b = pool.submit(() -> invoke(second, barrier));
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : List.of(a, b)) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    fail("a concurrent ownership request did not settle within 30s - the organization "
                            + "row lock appears never to be released");
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrency helper must terminate");
        }
    }

    private Outcome invoke(ConcurrentCall call, CyclicBarrier barrier) throws Exception {
        barrier.await(20, TimeUnit.SECONDS);
        try {
            return Outcome.accepted(call.run());
        } catch (NetheriteException e) {
            return Outcome.rejected(e.getErrorCode());
        }
    }

    private String describe(List<Outcome> outcomes) {
        return outcomes.stream()
                .map(o -> o.succeeded() ? "accepted:" + o.value() : "rejected:" + o.error())
                .toList()
                .toString();
    }

    /** The result of one racing request: the value written, or the refusal. */
    private record Outcome(UUID value, SystemErrorCode error) {
        static Outcome accepted(UUID value) {
            return new Outcome(value, null);
        }

        static Outcome rejected(SystemErrorCode code) {
            return new Outcome(null, code);
        }

        boolean succeeded() {
            return error == null;
        }
    }

    @FunctionalInterface
    private interface ConcurrentCall {
        UUID run();
    }
}
