package io.netherite.domain.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResourceDomainService {

    private final ResourceRepository resourceRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional
    public ResourceEntity createResource(
            UUID organizationId,
            String name,
            ConnectorType connectorType,
            String baseUrl,
            String encryptedCredentials,
            SensitivityLevel sensitivityLevel,
            String description) {

        if (!organizationRepository.existsById(organizationId)) {
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Organization not found: " + organizationId);
        }
        if (name == null || name.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Resource name cannot be blank");
        }
        if (connectorType == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Connector type is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS, "Base URL is required");
        }

        ResourceEntity resource = ResourceEntity.builder()
                .organizationId(organizationId)
                .name(name.trim())
                .connectorType(connectorType)
                .baseUrl(baseUrl.trim())
                .encryptedCredentials(encryptedCredentials)
                .sensitivityLevel(sensitivityLevel != null ? sensitivityLevel : SensitivityLevel.MEDIUM)
                .description(description)
                .status("ACTIVE")
                .build();

        return resourceRepository.save(resource);
    }

    @Transactional(readOnly = true)
    public ResourceEntity getResource(UUID organizationId, UUID resourceId) {
        return resourceRepository.findByIdAndOrganizationId(resourceId, organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND, "Resource not found in organization: " + resourceId));
    }

    @Transactional(readOnly = true)
    public List<ResourceEntity> listResources(UUID organizationId) {
        return resourceRepository.findAllByOrganizationId(organizationId);
    }
}
