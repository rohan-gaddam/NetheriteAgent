package io.netherite.domain.repository;

import io.netherite.domain.entity.OrganizationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    Optional<OrganizationEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Locks the organization row for the duration of the transaction.
     *
     * <p>Used by authorized-person assignment and transfer so that two concurrent
     * requests serialize on the same row. Without this, a read-modify-write pair
     * could let two transfers interleave and leave the organization in a state that
     * neither request intended.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrganizationEntity o where o.id = :id")
    Optional<OrganizationEntity> findByIdForUpdate(@Param("id") UUID id);
}
