package io.netherite.ingestion.catalog;

import io.netherite.domain.entity.AgentResourceBindingEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AgentResourceBindingRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.ingestion.model.RegisteredActionTarget;
import io.netherite.ingestion.spi.RegisteredActionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed {@link RegisteredActionCatalog}.
 *
 * <p>Resolves a candidate (resource, action) pair strictly against the registered
 * resource catalog and the agent's explicit, active binding. Every negative outcome is
 * fail-closed:</p>
 * <ul>
 *   <li>malformed org/agent identifiers -&gt; reject;</li>
 *   <li>resource missing, inactive, or matching more than once (ambiguous) -&gt; reject;</li>
 *   <li>no active binding for this agent -&gt; reject;</li>
 *   <li>binding with an empty allow-list -&gt; reject (never "allow all");</li>
 *   <li>action not explicitly present in the allow-list -&gt; reject.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DbRegisteredActionCatalog implements RegisteredActionCatalog {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String WILDCARD = "*";

    private final ResourceRepository resourceRepository;
    private final AgentResourceBindingRepository bindingRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<RegisteredActionTarget> resolve(String organizationId,
                                                    String agentId,
                                                    String resourceName,
                                                    String actionType) {
        if (isBlank(organizationId) || isBlank(agentId) || isBlank(resourceName) || isBlank(actionType)) {
            return Optional.empty();
        }

        UUID organizationUuid = parseUuid(organizationId).orElse(null);
        UUID agentUuid = parseUuid(agentId).orElse(null);
        if (organizationUuid == null || agentUuid == null) {
            return Optional.empty();
        }

        List<ResourceEntity> matches = resourceRepository.findAllByOrganizationId(organizationUuid).stream()
                .filter(resource -> resource.getName() != null
                        && resource.getName().equalsIgnoreCase(resourceName))
                .toList();
        if (matches.size() != 1) {
            return Optional.empty();
        }
        ResourceEntity resource = matches.get(0);
        if (!ACTIVE_STATUS.equals(resource.getStatus())) {
            return Optional.empty();
        }

        Optional<AgentResourceBindingEntity> binding =
                bindingRepository.findByOrganizationIdAndAgentIdAndResourceId(
                        organizationUuid, agentUuid, resource.getId());
        if (binding.isEmpty() || !binding.get().isActive()) {
            return Optional.empty();
        }

        List<String> allowedActions = binding.get().getAllowedActions();
        if (allowedActions == null || allowedActions.isEmpty()) {
            return Optional.empty();
        }
        boolean permitted = allowedActions.contains(WILDCARD) || allowedActions.contains(actionType);
        if (!permitted) {
            return Optional.empty();
        }

        return Optional.of(new RegisteredActionTarget(resource.getName()));
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
