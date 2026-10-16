package io.netherite.auth.session;

import io.netherite.domain.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically purges expired human sessions so stale rows cannot accumulate as
 * live-credential reservoirs or unbounded table growth. Sessions are keyed by a
 * SHA-256 digest, so purging by expiry window never touches real credentials.
 *
 * <p>Derived {@code deleteBy...} queries load each matching row and remove it via
 * {@code EntityManager}, so the purge runs inside a transaction — otherwise it
 * throws {@code TransactionRequiredException} and the stale sessions are never
 * purged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final UserSessionRepository userSessionRepository;

    @Scheduled(fixedRateString = "${netherite.security.session-cleanup-interval-ms:300000}")
    @Transactional
    public void purgeExpiredSessions() {
        long purged = userSessionRepository.deleteByExpiresAtBefore(Instant.now());
        if (purged > 0) {
            log.info("Purged {} expired user session(s)", purged);
        }
    }
}