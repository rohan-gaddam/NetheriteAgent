package io.netherite.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Response body from the Phase 5 connector execution endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectorExecutionApiResponse {

    private String executionId;
    private boolean successful;
    private int statusCode;
    private Map<String, Object> responseBody;
    private long executionDurationMillis;
    private String errorMessage;
    private String resource;
    private String connectorType;
}
