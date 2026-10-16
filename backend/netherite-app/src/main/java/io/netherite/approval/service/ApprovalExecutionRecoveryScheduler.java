package io.netherite.approval.service;

import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.state.config.NetheriteDistributedProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Recovers approved-but-unexecuted tickets whose execution claim is stale (its lease
 * expired because the owning replica crashed or stalled). Stale claims below the attempt
 * bound are re-claimed and rescheduled; exhausted claims are marked terminal so they are
 * never executed beyond the bound.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApprovalExecutionRecoveryScheduler {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalContinuationService continuationService;
    private final NetheriteDistributedProperties distributedProperties;

    @Scheduled(fixedDelayString = "${netherite.distributed.idempotency.approval-recovery-interval-ms:60000}")
    public void recoverStaleApprovalExecutions() {
        Instant now = Instant.now();
        int maxAttempts = distributedProperties.getIdempotency().getApprovalMaxAttempts();

        List<ApprovalRequestEntity> exhausted = approvalRequestRepository.findExhaustedExecutions(now, maxAttempts);
        for (ApprovalRequestEntity ticket : exhausted) {
            try {
                approvalRequestRepository.exhaustExecution(
                        ticket.getId(), now, "Approval execution exceeded maximum retry attempts");
                log.warn("Approval execution exhausted after {} attempts for ticket={} (org={})",
                        ticket.getExecutionAttempts(), ticket.getId(), ticket.getOrganizationId());
            } catch (Exception e) {
                log.error("Failed to exhaust approval execution for ticket {}: {}", ticket.getId(), e.getMessage());
            }
        }

        List<ApprovalRequestEntity> stale = approvalRequestRepository.findStaleExecutions(now, maxAttempts);
        for (ApprovalRequestEntity ticket : stale) {
            try {
                if (continuationService.claimExecutionAndSchedule(ticket.getId().toString())) {
                    log.warn("Recovered stale approval execution for ticket={} (attempt {} of {})",
                            ticket.getId(), ticket.getExecutionAttempts() + 1, maxAttempts);
                }
            } catch (Exception e) {
                log.error("Failed to recover stale approval execution for ticket {}: {}", ticket.getId(), e.getMessage());
            }
        }
    }
}