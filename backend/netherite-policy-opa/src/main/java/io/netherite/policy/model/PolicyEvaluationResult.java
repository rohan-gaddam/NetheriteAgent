package io.netherite.policy.model;

import io.netherite.common.model.DecisionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aggregated policy evaluation response from OPA before conflict resolution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyEvaluationResult {
    private List<PolicyRuleResult> ruleResults;
    private long evaluationDurationNanos;
    private boolean successful;
    private String errorMessage;
}
