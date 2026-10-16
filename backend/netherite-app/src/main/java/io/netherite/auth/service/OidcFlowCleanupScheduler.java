package io.netherite.auth.service;

import io.netherite.domain.repository.OidcFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OidcFlowCleanupScheduler {

    private final OidcFlowRepository oidcFlowRepository;

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void cleanupExpiredFlows() {
        int deleted = oidcFlowRepository.deleteExpiredFlows(Instant.now());
        if (deleted > 0) {
            log.debug("Cleaned up {} expired OIDC flows", deleted);
        }
    }
}
