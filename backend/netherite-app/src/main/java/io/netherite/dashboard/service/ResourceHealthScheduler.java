package io.netherite.dashboard.service;

import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.security.context.TenantContextHolder;
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
public class ResourceHealthScheduler {

    private static final String LOCK_KEY = "health-check:scheduler";

    private final HealthCheckService healthCheckService;
    private final OrganizationRepository organizationRepository;
    private final DistributedLockManager lockManager;

    @Scheduled(fixedRate = 3600000)
    public void scheduledHealthCheck() {
        runHealthCheckNow();
    }

    public void runHealthCheckNow() {
        // Watchdog lease: renewed while this node lives, expires promptly after a crash so
        // another replica picks up the next cycle. Exactly one replica scans per cycle.
        var lockOpt = lockManager.tryAcquireLock(LOCK_KEY, Duration.ZERO);
        if (lockOpt.isEmpty()) {
            log.debug("Health check already in progress on another replica, skipping");
            return;
        }
        DistributedLock lock = lockOpt.get();

        try {
            log.info("Starting health check cycle");
            Instant start = Instant.now();

            List<OrganizationEntity> organizations = organizationRepository.findAll();

            for (OrganizationEntity org : organizations) {
                TenantContextHolder.setContext(
                        io.netherite.common.context.TenantContext.builder()
                                .organizationId(org.getId().toString())
                                .organizationSlug(org.getSlug())
                                .principalType("SYSTEM")
                                .build());

                try {
                    healthCheckService.checkAllResources(org.getId());
                } catch (Exception e) {
                    log.error("Health check failed for organization {}: {}", org.getSlug(), e.getMessage());
                } finally {
                    TenantContextHolder.clear();
                }
            }

            log.info("Health check cycle completed in {}ms", Duration.between(start, Instant.now()).toMillis());
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("Failed to release health check lock: {}", e.getMessage());
            }
        }
    }
}
