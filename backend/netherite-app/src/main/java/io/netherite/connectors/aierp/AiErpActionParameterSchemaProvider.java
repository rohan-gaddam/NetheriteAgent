package io.netherite.connectors.aierp;

import io.netherite.ingestion.model.ActionParameterSchema;
import io.netherite.ingestion.spi.ActionParameterSchemaProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Provides canonical parameter schemas for registered AI-ERP actions.
 * Ordered after database provider so tenant-customized database schemas take precedence.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class AiErpActionParameterSchemaProvider implements ActionParameterSchemaProvider {

    private final AiErpActionDefinitionRegistry registry;

    @Override
    public Optional<ActionParameterSchema> schemaFor(String organizationId, String resourceName, String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return Optional.empty();
        }
        return registry.findDefinition(actionType).map(AiErpActionDefinition::getParameterSchema);
    }
}
