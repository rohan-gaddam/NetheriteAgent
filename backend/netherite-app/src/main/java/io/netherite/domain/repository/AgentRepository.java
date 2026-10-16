package io.netherite.domain.repository;

import io.netherite.domain.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

    List<AgentEntity> findAllByOrganizationId(UUID organizationId);

    Optional<AgentEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);
}
