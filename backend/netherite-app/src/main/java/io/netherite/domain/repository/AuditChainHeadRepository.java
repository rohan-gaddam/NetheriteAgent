package io.netherite.domain.repository;

import io.netherite.domain.entity.AuditChainHeadEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditChainHeadRepository extends JpaRepository<AuditChainHeadEntity, UUID> {

    /**
     * Loads (or blocks on) the per-org chain head row, serializing audit chaining across
     * replicas. Two concurrent writers in one organization cannot read the same previous
     * hash and fork the chain.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM AuditChainHeadEntity h WHERE h.organizationId = :orgId")
    Optional<AuditChainHeadEntity> findByOrganizationIdForUpdate(@Param("orgId") UUID organizationId);
}