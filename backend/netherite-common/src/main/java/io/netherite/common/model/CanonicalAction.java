package io.netherite.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Internal canonical representation of an action requested by an AI agent.
 * All incoming agent protocols (REST, MCP, custom JSON) normalize into this strongly-typed model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CanonicalAction {

    @NotBlank(message = "actionId is required")
    private String actionId;

    @NotBlank(message = "organizationId is required")
    private String organizationId;

    @NotBlank(message = "agentId is required")
    private String agentId;

    private String userId;

    @NotBlank(message = "actionType is required")
    private String actionType;

    @NotBlank(message = "targetResource is required")
    private String targetResource;

    @NotNull(message = "parameters map is required")
    private Map<String, Object> parameters;

    private String idempotencyKey;

    @NotNull(message = "timestamp is required")
    private Instant timestamp;

    private SourceMetadata sourceMetadata;
}
