package io.netherite.connectors.model;

import io.netherite.common.model.CanonicalAction;
import io.netherite.routing.model.ResourceRoute;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Execution dispatch payload for a resource connector.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorExecutionRequest {
    private String executionId;
    private String idempotencyKey;
    private CanonicalAction action;
    private ResourceRoute route;
    private Map<String, String> resolvedCredentials;
    private long timeoutMillis;
}
