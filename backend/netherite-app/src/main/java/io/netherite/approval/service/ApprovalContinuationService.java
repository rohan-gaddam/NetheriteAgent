package io.netherite.approval.service;

import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.service.NodeIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Coordinates the asynchronous continuation of approved tickets with an exclusive,
 * cross-replica execution claim (lease + attempt bound). The claim is taken atomically in
 * the database before any async work is scheduled, so exactly one replica executes a given
 * approved ticket. If that replica crashes, {@link ApprovalExecutionRecoveryScheduler}
 * re-claims the expired lease and retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalContinuationService {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalExecutionRunner executionRunner;
    private final NodeIdentity nodeIdentity;
    private final NetheriteDistributedProperties distributedProperties;

    /**
     * Claims the continuation right for an APPROVED ticket and schedules the async execution.
     *
     * @return {@code true} if this replica acquired the claim (and therefore will run it);
     *         {@code false} when another replica already owns a live claim or the ticket is
     *         terminal.
     */
    @Transactional
    public boolean claimExecutionAndSchedule(String ticketId) {
        UUID ticketUuid;
        try {
            ticketUuid = UUID.fromString(ticketId);
        } catch (IllegalArgumentException e) {
            log.warn("Approval continuation claim skipped - invalid ticket id {}", ticketId);
            return false;
        }

        Instant claimedAt = Instant.now();
        Instant leaseExpiry = claimedAt.plus(Duration.ofMinutes(
                distributedProperties.getIdempotency().getApprovalLeaseMinutes()));

        int claimed = approvalRequestRepository.claimExecution(
                ticketUuid, nodeIdentity.getId(), claimedAt, leaseExpiry);
        if (claimed != 1) {
            log.info("Approval continuation claim skipped for ticket={} (other replica active or terminal)",
                    ticketId);
            return false;
        }

        log.info("Approval continuation claim acquired for ticket={} by node={} (lease until {})",
                ticketId, nodeIdentity.getId(), leaseExpiry);
        executionRunner.execute(ticketId, nodeIdentity.getId());
        return true;
    }
}