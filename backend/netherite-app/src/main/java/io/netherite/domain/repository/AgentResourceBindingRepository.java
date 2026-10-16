package io.netherite.domain.repository;

import io.netherite.domain.entity.AgentResourceBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentResourceBindingRepository extends JpaRepository<AgentResourceBindingEntity, UUID> {

    List<AgentResourceBindingEntity> findAllByOrganizationId(UUID organizationId);

    List<AgentResourceBindingEntity> findAllByAgentIdAndOrganizationId(UUID agentId, UUID organizationId);

    Optional<AgentResourceBindingEntity> findByOrganizationIdAndAgentIdAndResourceId(UUID organizationId, UUID agentId, UUID resourceId);

    boolean existsByOrganizationIdAndAgentIdAndResourceId(UUID organizationId, UUID agentId, UUID resourceId);
}
