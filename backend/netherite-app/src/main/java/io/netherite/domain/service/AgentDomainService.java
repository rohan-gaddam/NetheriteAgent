package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.AgentApiKeyEntity;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.repository.AgentApiKeyRepository;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.security.key.ApiKeyHashService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentDomainService {

    private final AgentRepository agentRepository;
    private final AgentApiKeyRepository apiKeyRepository;
    private final OrganizationRepository organizationRepository;
    private final ApiKeyHashService apiKeyHashService = new ApiKeyHashService();

    public record AgentRegistrationResult(AgentEntity agent, String keyIdentifier, String secretToken, String fullApiKey) {}
    public record GeneratedApiKeyResult(UUID keyId, String keyIdentifier, String secretToken, String fullApiKey) {}

    @Transactional
    public AgentRegistrationResult registerAgent(UUID organizationId, String name, String description, String trustTier) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Organization not found: " + organizationId);
        }
        if (name == null || name.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Agent name cannot be blank");
        }

        AgentEntity agent = AgentEntity.builder()
                .organizationId(organizationId)
                .name(name.trim())
                .description(description)
                .trustTier(trustTier != null ? trustTier : "TIER_1")
                .status("ACTIVE")
                .build();

        AgentEntity savedAgent = agentRepository.save(agent);

        // Generate initial secure API key
        ApiKeyHashService.GeneratedApiKey generatedKey = apiKeyHashService.generateApiKey();

        AgentApiKeyEntity apiKeyEntity = AgentApiKeyEntity.builder()
                .agentId(savedAgent.getId())
                .organizationId(organizationId)
                .keyIdentifier(generatedKey.keyIdentifier())
                .secretHash(generatedKey.hashedSecret())
                .description("Initial API Key")
                .build();

        apiKeyRepository.save(apiKeyEntity);

        return new AgentRegistrationResult(
                savedAgent,
                generatedKey.keyIdentifier(),
                generatedKey.secretToken(),
                generatedKey.fullApiKey()
        );
    }

    @Transactional
    public GeneratedApiKeyResult createApiKey(UUID organizationId, UUID agentId, String description, Instant expiresAt) {
        if (!agentRepository.existsByIdAndOrganizationId(agentId, organizationId)) {
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Agent not found in organization: " + agentId);
        }

        ApiKeyHashService.GeneratedApiKey generatedKey = apiKeyHashService.generateApiKey();

        AgentApiKeyEntity apiKeyEntity = AgentApiKeyEntity.builder()
                .agentId(agentId)
                .organizationId(organizationId)
                .keyIdentifier(generatedKey.keyIdentifier())
                .secretHash(generatedKey.hashedSecret())
                .description(description != null ? description : "API Key")
                .expiresAt(expiresAt)
                .build();

        AgentApiKeyEntity saved = apiKeyRepository.save(apiKeyEntity);

        return new GeneratedApiKeyResult(
                saved.getId(),
                generatedKey.keyIdentifier(),
                generatedKey.secretToken(),
                generatedKey.fullApiKey()
        );
    }

    @Transactional
    public GeneratedApiKeyResult rotateApiKey(UUID organizationId, UUID agentId, UUID oldKeyId, String description, Instant newExpiresAt) {
        // 1. Create new key
        GeneratedApiKeyResult newKey = createApiKey(organizationId, agentId, description, newExpiresAt);

        // 2. Revoke old key if provided
        if (oldKeyId != null) {
            revokeApiKey(organizationId, oldKeyId);
        }

        return newKey;
    }

    @Transactional(readOnly = true)
    public AgentEntity getAgent(UUID organizationId, UUID agentId) {
        return agentRepository.findByIdAndOrganizationId(agentId, organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Agent not found in organization: " + agentId));
    }

    @Transactional(readOnly = true)
    public List<AgentEntity> listAgents(UUID organizationId) {
        return agentRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional
    public void revokeApiKey(UUID organizationId, UUID keyId) {
        AgentApiKeyEntity key = apiKeyRepository.findByIdAndOrganizationId(keyId, organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "API Key not found in organization: " + keyId));

        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);
    }
}
