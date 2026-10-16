package io.netherite.domain.repository;

import io.netherite.domain.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Optional<AuditLogEntity> findByOrganizationIdAndRequestId(UUID organizationId, String requestId);

    List<AuditLogEntity> findAllByOrganizationIdAndRequestId(UUID organizationId, String requestId);

    List<AuditLogEntity> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    @Query("SELECT a FROM AuditLogEntity a WHERE a.organizationId = :orgId ORDER BY a.createdAt ASC")
    List<AuditLogEntity> findAllByOrganizationIdInOrder(@Param("orgId") UUID organizationId);

    Optional<AuditLogEntity> findTopByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.organizationId = :orgId AND a.createdAt >= :since")
    long countByOrganizationIdAndCreatedAtAfter(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.organizationId = :orgId AND a.createdAt >= :since AND a.decision = :decision")
    long countByOrganizationIdAndCreatedAtAfterAndDecision(@Param("orgId") UUID orgId, @Param("since") Instant since, @Param("decision") String decision);

    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.organizationId = :orgId AND a.createdAt >= :since AND a.eventType = :eventType")
    long countByOrganizationIdAndCreatedAtAfterAndEventType(@Param("orgId") UUID orgId, @Param("since") Instant since, @Param("eventType") String eventType);

    @Query("SELECT DISTINCT a.agentId FROM AuditLogEntity a WHERE a.organizationId = :orgId AND a.createdAt >= :since AND a.agentId IS NOT NULL")
    List<UUID> findDistinctAgentIdsWithActivitySince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT a FROM AuditLogEntity a WHERE a.organizationId = :orgId ORDER BY a.createdAt DESC")
    List<AuditLogEntity> findTop10ByOrganizationId(@Param("orgId") UUID orgId, org.springframework.data.domain.Pageable pageable);
}
