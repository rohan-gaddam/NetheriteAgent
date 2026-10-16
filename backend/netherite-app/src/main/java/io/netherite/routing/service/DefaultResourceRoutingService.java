package io.netherite.routing.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of ResourceRoutingService.
 * Resolves enterprise resource routes from CanonicalActions using the database,
 * enforcing tenant isolation and agent-resource binding authorization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultResourceRoutingService implements ResourceRoutingService {

    private final ResourceRepository resourceRepository;
    private final BindingDomainService bindingDomainService;

    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceRoute> resolveRoute(CanonicalAction action) {
        if (action == null || action.getOrganizationId() == null || action.getTargetResource() == null) {
            return Optional.empty();
        }

        UUID organizationId;
        try {
            organizationId = UUID.fromString(action.getOrganizationId());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid organizationId in action: {}", action.getOrganizationId());
            return Optional.empty();
        }

        // Find resource by name within the tenant's organization
        Optional<ResourceEntity> resourceOpt = resourceRepository.findByNameAndOrganizationId(
                action.getTargetResource(), organizationId);

        if (resourceOpt.isEmpty()) {
            log.warn("Resource '{}' not found for organization '{}'", action.getTargetResource(), action.getOrganizationId());
            return Optional.empty();
        }

        ResourceEntity resource = resourceOpt.get();

        if (!"ACTIVE".equals(resource.getStatus())) {
            log.warn("Resource '{}' is not active (status: {})", resource.getName(), resource.getStatus());
            return Optional.empty();
        }

        ResourceRoute route = ResourceRoute.builder()
                .resourceId(resource.getId().toString())
                .resourceName(resource.getName())
                .connectorType(resource.getConnectorType())
                .baseUrl(resource.getBaseUrl())
                .sensitivityLevel(resource.getSensitivityLevel())
                .build();

        return Optional.of(route);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBindingAuthorized(String organizationId, String agentId, String resourceId, String actionType) {
        try {
            UUID orgUUID = UUID.fromString(organizationId);
            UUID agentUUID = UUID.fromString(agentId);
            UUID resourceUUID = UUID.fromString(resourceId);

            return bindingDomainService.isActionAuthorized(orgUUID, agentUUID, resourceUUID, actionType);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID in binding authorization check: org={}, agent={}, resource={}",
                    organizationId, agentId, resourceId);
            return false;
        }
    }
}
