package io.netherite.ingestion.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Raw action ingestion request from an AI agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionIngestionRequest {

    @NotBlank(message = "action is required")
    private String action;

    @NotBlank(message = "resource is required")
    private String resource;

    private Map<String, Object> parameters;
    private Map<String, Object> userContext;
    private String rawPrompt;
}
