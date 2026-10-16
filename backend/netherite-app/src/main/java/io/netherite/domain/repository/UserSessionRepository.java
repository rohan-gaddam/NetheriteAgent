package io.netherite.domain.repository;

import io.netherite.domain.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session persistence. Security contract: the {@code session_token} column stores
 * {@code SHA-256(rawToken)} — the plaintext credential only ever lives in the HttpOnly
 * cookie. Every caller must persist and look up via {@link io.netherite.auth.session.SessionTokenHasher#hash}.
 */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {

    /** Lookup by the SHA-256 digest of the raw session token ({@code SessionTokenHasher.hash(...)}). */
    Optional<UserSessionEntity> findBySessionToken(String tokenHash);

    long deleteByExpiresAtBefore(Instant cutoff);

    List<UserSessionEntity> findByOrganizationId(UUID orgId);
}
