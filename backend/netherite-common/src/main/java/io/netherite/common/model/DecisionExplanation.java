package io.netherite.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Transparent explanation of the decision, detailing which policies matched and the deterministic precedence applied.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionExplanation {
    private DecisionType finalDecision;
    private String primaryReason;
    private List<String> contributingPolicies;
    private List<String> ruleEvaluations;
    private String resolutionRule;
}
