package io.netherite.routing.model;

import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Resolved enterprise resource destination for action dispatch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceRoute {
    private String resourceId;
    private String resourceName;
    private ConnectorType connectorType;
    private String baseUrl;
    private String pathTemplate;
    private String httpMethod;
    private String credentialReference;
    private SensitivityLevel sensitivityLevel;
    private Map<String, Object> connectorConfig;
}
