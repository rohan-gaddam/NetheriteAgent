package io.netherite.approval.service;

import io.netherite.approval.spi.ApprovalService;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalExpirationScheduler {

    private static final String LOCK_KEY = "approval-expiration:scheduler";

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalService approvalService;
    private final DistributedLockManager lockManager;

    @Scheduled(fixedRate = 60000)
    public void expireStaleApprovals() {
        DistributedLock lock = null;
        var lockOpt = lockManager.tryAcquireLock(LOCK_KEY, Duration.ZERO);
        if (lockOpt.isEmpty()) {
            log.debug("Approval expiration already in progress on another replica, skipping");
            return;
        }
        lock = lockOpt.get();

        try {
            Instant now = Instant.now();
            List<ApprovalRequestEntity> expiredTickets = approvalRequestRepository.findAllExpiredPending(now);

            for (ApprovalRequestEntity ticket : expiredTickets) {
                try {
                    approvalService.expireTicket(ticket.getId().toString());
                    log.info("Auto-expired approval ticket {} for org={}", ticket.getId(), ticket.getOrganizationId());
                } catch (Exception e) {
                    log.error("Failed to auto-expire approval ticket {}: {}", ticket.getId(), e.getMessage());
                }
            }
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("Failed to release approval expiration lock: {}", e.getMessage());
            }
        }
    }
}