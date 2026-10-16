package io.netherite.ingestion.provider;

import io.netherite.domain.entity.ActionParameterSchemaEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ActionParameterSchemaRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.ingestion.model.ActionParameterSchema;
import io.netherite.ingestion.schema.ActionParameterSchemaParser;
import io.netherite.ingestion.spi.ActionParameterSchemaProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed {@link ActionParameterSchemaProvider}.
 *
 * <p>Resolves the <b>active</b>, operator-authored schema for a registered
 * (resource, action) pair. Every negative outcome is fail-closed:</p>
 * <ul>
 *   <li>malformed organization identifier -&gt; no schema;</li>
 *   <li>resource missing, inactive, or matching more than once (ambiguous) -&gt; no schema;</li>
 *   <li>no schema row, or no <em>active</em> version -&gt; no schema;</li>
 *   <li>a stored definition that no longer parses/validates -&gt; throw, so the promoter
 *       fails closed instead of executing against a schema it cannot trust.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DbActionParameterSchemaProvider implements ActionParameterSchemaProvider {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final ResourceRepository resourceRepository;
    private final ActionParameterSchemaRepository schemaRepository;
    private final ActionParameterSchemaParser parser;

    @Override
    @Transactional(readOnly = true)
    public Optional<ActionParameterSchema> schemaFor(String organizationId,
                                                     String resourceName,
                                                     String actionType) {
        if (isBlank(organizationId) || isBlank(resourceName) || isBlank(actionType)) {
            return Optional.empty();
        }
        UUID organizationUuid = parseUuid(organizationId).orElse(null);
        if (organizationUuid == null) {
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

        Optional<ActionParameterSchemaEntity> active =
                schemaRepository.findByOrganizationIdAndResourceIdAndActionTypeAndStatus(
                        organizationUuid, resource.getId(), actionType, ACTIVE_STATUS);
        if (active.isEmpty()) {
            return Optional.empty();
        }

        // Re-validate on read: a definition that cannot be trusted must not be enforced as
        // a weaker rule. Throwing propagates to the promoter, which fails closed.
        return Optional.of(parser.parse(active.get().getSchemaDefinition()));
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
