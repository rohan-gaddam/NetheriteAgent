package io.netherite.policy.model;

import io.netherite.common.model.DecisionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Outcome from a single evaluated Rego policy rule.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyRuleResult {
    private String policyId;
    private String policyName;
    private int policyVersion;
    private String ruleName;
    private DecisionType decision;
    private String reason;
    private Map<String, Object> details;
}
