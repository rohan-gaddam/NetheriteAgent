package io.netherite.domain.repository;

import io.netherite.domain.entity.OidcFlowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OidcFlowRepository extends JpaRepository<OidcFlowEntity, UUID> {

    Optional<OidcFlowEntity> findByState(String state);

    @Modifying
    @Query("DELETE FROM OidcFlowEntity f WHERE f.expiresAt < :now")
    int deleteExpiredFlows(@Param("now") Instant now);
}
