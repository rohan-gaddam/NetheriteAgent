package io.netherite.connectors.aierp;

import io.netherite.domain.entity.ActionRouteEntity;
import io.netherite.domain.repository.ActionRouteRepository;
import io.netherite.routing.model.ResourceRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves action-specific routing for enterprise resources.
 * 
 * Precedence:
 * 1. Operator-configured custom route in `action_routes` table.
 * 2. Authoritative registry built-in definition from `AiErpActionDefinitionRegistry`.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiErpActionRouteService {

    private final ActionRouteRepository actionRouteRepository;
    private final AiErpActionDefinitionRegistry registry;

    /**
     * Enriches a ResourceRoute with action-specific HTTP method, path template, and connector config.
     */
    public ResourceRoute enrichRouteForAction(ResourceRoute baseRoute, UUID orgId, UUID resourceId, String actionType) {
        if (baseRoute == null || actionType == null || actionType.isBlank()) {
            return baseRoute;
        }

        // 1. Check database overrides first (operator-configured)
        Optional<ActionRouteEntity> dbRoute = actionRouteRepository
                .findByOrganizationIdAndResourceIdAndActionTypeAndActiveTrue(orgId, resourceId, actionType);

        if (dbRoute.isPresent()) {
            ActionRouteEntity entity = dbRoute.get();
            log.debug("Found database-configured action route for action '{}' on resource '{}'", actionType, resourceId);
            return ResourceRoute.builder()
                    .resourceId(baseRoute.getResourceId())
                    .resourceName(baseRoute.getResourceName())
                    .connectorType(baseRoute.getConnectorType())
                    .baseUrl(baseRoute.getBaseUrl())
                    .pathTemplate(entity.getPathTemplate())
                    .httpMethod(entity.getHttpMethod())
                    .credentialReference(baseRoute.getCredentialReference())
                    .sensitivityLevel(baseRoute.getSensitivityLevel())
                    .connectorConfig(entity.getConnectorConfig() != null ? entity.getConnectorConfig() : baseRoute.getConnectorConfig())
                    .build();
        }

        // 2. Check registry for built-in definition
        Optional<AiErpActionDefinition> definition = registry.findDefinition(actionType);
        if (definition.isPresent()) {
            AiErpActionDefinition def = definition.get();
            log.debug("Found registry-defined action route for action '{}': method={}, path={}",
                    actionType, def.getHttpMethod(), def.getPathTemplate());
            return ResourceRoute.builder()
                    .resourceId(baseRoute.getResourceId())
                    .resourceName(baseRoute.getResourceName())
                    .connectorType(baseRoute.getConnectorType())
                    .baseUrl(baseRoute.getBaseUrl())
                    .pathTemplate(def.getPathTemplate())
                    .httpMethod(def.getHttpMethod())
                    .credentialReference(baseRoute.getCredentialReference())
                    .sensitivityLevel(def.getSensitivityLevel() != null ? def.getSensitivityLevel() : baseRoute.getSensitivityLevel())
                    .connectorConfig(baseRoute.getConnectorConfig())
                    .build();
        }

        // Return base route as-is if no action-specific route is known
        return baseRoute;
    }
}
