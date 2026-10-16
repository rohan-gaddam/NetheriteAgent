package io.netherite.connectors.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Execution result received from the enterprise resource.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorExecutionResult {
    private boolean successful;
    private int statusCode;
    private Map<String, Object> responseBody;
    private long executionDurationMillis;
    private String errorMessage;
}
