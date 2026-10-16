package io.netherite.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request body for the Phase 5 connector execution endpoint.
 * The authenticated agent's identity is derived from the security context, not this request body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectorExecutionApiRequest {

    @NotBlank(message = "action is required")
    private String action;

    @NotBlank(message = "resource is required")
    private String resource;

    private Map<String, Object> parameters;
}
