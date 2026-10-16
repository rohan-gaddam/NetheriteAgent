package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.AgentResourceBindingEntity;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.AgentResourceBindingRepository;
import io.netherite.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BindingDomainService {

    private final AgentResourceBindingRepository bindingRepository;
    private final AgentRepository agentRepository;
    private final ResourceRepository resourceRepository;

    @Transactional
    public AgentResourceBindingEntity bindAgentToResource(
            UUID organizationId,
            UUID agentId,
            UUID resourceId,
            List<String> allowedActions) {

        // Validate agent belongs to this organization
        if (!agentRepository.existsByIdAndOrganizationId(agentId, organizationId)) {
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                    "Agent not found in organization: " + agentId);
        }

        // Validate resource belongs to this organization
        if (!resourceRepository.existsByIdAndOrganizationId(resourceId, organizationId)) {
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                    "Resource not found in organization: " + resourceId);
        }

        // Check if binding already exists
        Optional<AgentResourceBindingEntity> existing = bindingRepository
                .findByOrganizationIdAndAgentIdAndResourceId(organizationId, agentId, resourceId);

        if (existing.isPresent()) {
            AgentResourceBindingEntity binding = existing.get();
            binding.setAllowedActions(allowedActions != null ? allowedActions : List.of());
            binding.setActive(true);
            return bindingRepository.save(binding);
        }

        AgentResourceBindingEntity binding = AgentResourceBindingEntity.builder()
                .organizationId(organizationId)
                .agentId(agentId)
                .resourceId(resourceId)
                .allowedActions(allowedActions != null ? allowedActions : List.of())
                .active(true)
                .build();

        return bindingRepository.save(binding);
    }

    @Transactional(readOnly = true)
    public AgentResourceBindingEntity getRequiredBinding(UUID organizationId, UUID agentId, UUID resourceId) {
        return bindingRepository.findByOrganizationIdAndAgentIdAndResourceId(organizationId, agentId, resourceId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                        "Agent is not bound to this resource in organization"));
    }

    @Transactional(readOnly = true)
    public boolean isActionAuthorized(UUID organizationId, UUID agentId, UUID resourceId, String actionType) {
        Optional<AgentResourceBindingEntity> bindingOpt = bindingRepository
                .findByOrganizationIdAndAgentIdAndResourceId(organizationId, agentId, resourceId);

        if (bindingOpt.isEmpty() || !bindingOpt.get().isActive()) {
            return false;
        }

        List<String> allowed = bindingOpt.get().getAllowedActions();
        if (allowed == null || allowed.isEmpty()) {
            return true; // No action restrictions configured on binding
        }

        return allowed.contains("*") || allowed.contains(actionType);
    }

    @Transactional
    public void unbindAgentFromResource(UUID organizationId, UUID agentId, UUID resourceId) {
        AgentResourceBindingEntity binding = getRequiredBinding(organizationId, agentId, resourceId);
        binding.setActive(false);
        bindingRepository.save(binding);
    }
}
