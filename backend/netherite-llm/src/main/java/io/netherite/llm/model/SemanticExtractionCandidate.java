package io.netherite.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Untrusted candidate output produced by an LLM during semantic translation.
 * Must undergo rigorous deterministic validation before becoming a CanonicalAction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticExtractionCandidate {
    private String candidateActionType;
    private String candidateTargetResource;
    private Map<String, Object> candidateParameters;
    private String rawModelOutput;
    private String modelId;
}
