package io.netherite.domain.repository;

import io.netherite.domain.entity.ResourceHealthEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResourceHealthRepository extends JpaRepository<ResourceHealthEntity, UUID> {

    List<ResourceHealthEntity> findAllByOrganizationIdOrderByResourceName(UUID organizationId);

    Optional<ResourceHealthEntity> findByResourceId(UUID resourceId);

    Optional<ResourceHealthEntity> findByResourceIdAndOrganizationId(UUID resourceId, UUID organizationId);

    boolean existsByResourceId(UUID resourceId);
}
