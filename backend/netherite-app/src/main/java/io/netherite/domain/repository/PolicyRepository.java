package io.netherite.domain.repository;

import io.netherite.domain.entity.PolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, UUID> {

    List<PolicyEntity> findAllByOrganizationId(UUID organizationId);

    Optional<PolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<PolicyEntity> findAllByOrganizationIdAndStatus(UUID organizationId, String status);
}
