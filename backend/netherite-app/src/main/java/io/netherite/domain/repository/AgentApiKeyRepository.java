package io.netherite.domain.repository;

import io.netherite.domain.entity.AgentApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentApiKeyRepository extends JpaRepository<AgentApiKeyEntity, UUID> {

    List<AgentApiKeyEntity> findAllByAgentIdAndOrganizationId(UUID agentId, UUID organizationId);

    Optional<AgentApiKeyEntity> findByKeyIdentifier(String keyIdentifier);

    Optional<AgentApiKeyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
