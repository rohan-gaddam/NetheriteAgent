package io.netherite.domain.repository;

import io.netherite.domain.entity.ApprovalRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, UUID> {

    Optional<ApprovalRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ApprovalRequestEntity> findAllByOrganizationIdAndStatus(UUID organizationId, String status);

    List<ApprovalRequestEntity> findAllByOrganizationId(UUID organizationId);

    List<ApprovalRequestEntity> findAllByOrganizationIdAndAgentIdAndStatus(UUID orgId, UUID agentId, String status);

    List<ApprovalRequestEntity> findAllByOrganizationIdAndTargetResourceAndStatus(UUID orgId, String resource, String status);

    List<ApprovalRequestEntity> findAllByOrganizationIdAndActionTypeAndStatus(UUID orgId, String actionType, String status);

    @Query("SELECT a FROM ApprovalRequestEntity a WHERE a.organizationId = :orgId AND a.status = 'PENDING' AND a.expiresAt < :now")
    List<ApprovalRequestEntity> findExpiredPending(@Param("orgId") UUID orgId, @Param("now") Instant now);

    @Query("SELECT a FROM ApprovalRequestEntity a WHERE a.status = 'PENDING' AND a.expiresAt < :now")
    List<ApprovalRequestEntity> findAllExpiredPending(@Param("now") Instant now);

    boolean existsByOrganizationIdAndStatusAndActionTypeAndTargetResource(
            UUID organizationId, String status, String actionType, String targetResource);

    /**
     * Atomically claims the asynchronous continuation of an APPROVED ticket: grants an
     * exclusive lease (single executor per ticket across replicas), increments the attempt
     * counter, and attributes the claim to {@code nodeId}. Safe against concurrent claims:
     * only the first UPDATE wins, the rest observe 0 rows. Pass {@code claimedAt} also as
     * the lease-expiry comparison so a stale (expired) claim can be taken over.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ApprovalRequestEntity a SET
                a.executionInProgress = true,
                a.executionClaimStartedAt = :claimedAt,
                a.executionLeaseExpiresAt = :leaseExpiry,
                a.executionClaimedBy = :nodeId,
                a.executionAttempts = a.executionAttempts + 1
            WHERE a.id = :id AND a.status = 'APPROVED'
              AND a.executionResult IS NULL AND a.executionError IS NULL
              AND (a.executionInProgress = false OR a.executionLeaseExpiresAt IS NULL
                   OR a.executionLeaseExpiresAt < :claimedAt)
            """)
    int claimExecution(@Param("id") UUID id, @Param("nodeId") String nodeId,
                       @Param("claimedAt") Instant claimedAt, @Param("leaseExpiry") Instant leaseExpiry);

    /** Releases a claim (transient failure / pre-claim abort) without recording an error. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ApprovalRequestEntity a SET
                a.executionInProgress = false,
                a.executionClaimStartedAt = NULL,
                a.executionLeaseExpiresAt = NULL,
                a.executionClaimedBy = NULL
            WHERE a.id = :id AND a.executionInProgress = true AND a.executionClaimedBy = :nodeId
            """)
    int releaseExecutionClaim(@Param("id") UUID id, @Param("nodeId") String nodeId);

    /** Records a successful execution result and releases the claim. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ApprovalRequestEntity a SET
                a.executionResult = :result,
                a.executionInProgress = false,
                a.executionClaimStartedAt = NULL,
                a.executionLeaseExpiresAt = NULL,
                a.executionClaimedBy = NULL
            WHERE a.id = :id AND a.executionInProgress = true AND a.executionClaimedBy = :nodeId
            """)
    int completeExecution(@Param("id") UUID id, @Param("nodeId") String nodeId,
                          @Param("result") String result);

    /** Records a terminal failure (deterministic) and releases the claim. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ApprovalRequestEntity a SET
                a.executionError = :error,
                a.executionInProgress = false,
                a.executionClaimStartedAt = NULL,
                a.executionLeaseExpiresAt = NULL,
                a.executionClaimedBy = NULL
            WHERE a.id = :id AND a.executionInProgress = true AND a.executionClaimedBy = :nodeId
            """)
    int failExecution(@Param("id") UUID id, @Param("nodeId") String nodeId, @Param("error") String error);

    /**
     * Marks a stale claim terminal once the attempt bound was reached (any owner's claim:
     * the lease already expired, so the previous owner is gone).
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ApprovalRequestEntity a SET
                a.executionError = :error,
                a.executionInProgress = false,
                a.executionClaimStartedAt = NULL,
                a.executionLeaseExpiresAt = NULL,
                a.executionClaimedBy = NULL
            WHERE a.id = :id AND a.executionInProgress = true
              AND a.executionLeaseExpiresAt IS NOT NULL AND a.executionLeaseExpiresAt < :now
              AND a.executionResult IS NULL AND a.executionError IS NULL
            """)
    int exhaustExecution(@Param("id") UUID id, @Param("now") Instant now, @Param("error") String error);

    /** Stale claims below the attempt bound - eligible for re-claim by another replica. */
    @Query("""
            SELECT a FROM ApprovalRequestEntity a
            WHERE a.status = 'APPROVED'
              AND a.executionInProgress = true
              AND a.executionLeaseExpiresAt IS NOT NULL AND a.executionLeaseExpiresAt < :now
              AND a.executionAttempts < :maxAttempts
              AND a.executionResult IS NULL AND a.executionError IS NULL
            """)
    List<ApprovalRequestEntity> findStaleExecutions(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /** Stale claims that exhausted their attempt bound - to be marked terminal. */
    @Query("""
            SELECT a FROM ApprovalRequestEntity a
            WHERE a.status = 'APPROVED'
              AND a.executionInProgress = true
              AND a.executionLeaseExpiresAt IS NOT NULL AND a.executionLeaseExpiresAt < :now
              AND a.executionAttempts >= :maxAttempts
              AND a.executionResult IS NULL AND a.executionError IS NULL
            """)
    List<ApprovalRequestEntity> findExhaustedExecutions(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);
}