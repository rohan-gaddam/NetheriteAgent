package io.netherite.connectors.aierp;

import io.netherite.common.model.SensitivityLevel;
import io.netherite.ingestion.model.ActionParameterSchema;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Authoritative definition of an AI-ERP action.
 * Holds canonical identifier, HTTP method, endpoint path template,
 * parameter schema, sensitivity/risk level, and documentation description.
 */
@Getter
@Builder
public class AiErpActionDefinition {
    private final String actionId;
    private final String httpMethod;
    private final String pathTemplate;
    private final String description;
    private final SensitivityLevel sensitivityLevel;
    private final ActionParameterSchema parameterSchema;
    private final boolean readOnly;
    private final Map<String, Object> defaultConnectorConfig;
}
