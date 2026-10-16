package io.netherite.ingestion.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.netherite.common.model.DecisionExplanation;
import io.netherite.common.model.DecisionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Standard response returned to an AI agent post-evaluation / execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionIngestionResponse {
    private String actionId;
    private DecisionType decision;
    private String status; // COMPLETED, PENDING_APPROVAL, REJECTED, FAILED
    private String statusUrl;
    private Map<String, Object> executionResult;
    private DecisionExplanation explanation;
}
