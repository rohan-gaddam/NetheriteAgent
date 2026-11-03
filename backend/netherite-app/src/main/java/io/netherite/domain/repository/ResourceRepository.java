package io.netherite.domain.repository;

import io.netherite.domain.entity.ResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResourceRepository extends JpaRepository<ResourceEntity, UUID> {

    List<ResourceEntity> findAllByOrganizationId(UUID organizationId);

    Optional<ResourceEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<ResourceEntity> findByNameAndOrganizationId(String name, UUID organizationId);

    Optional<ResourceEntity> findByNameIgnoreCaseAndOrganizationId(String name, UUID organizationId);

    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);
}
