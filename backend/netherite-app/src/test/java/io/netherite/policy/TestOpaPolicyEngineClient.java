package io.netherite.policy;

import io.netherite.common.model.DecisionType;
import io.netherite.context.model.DecisionContext;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test-only OPA client that simulates policy evaluation without requiring a real OPA server.
 * Evaluates basic rules: missing resource → DENY, missing agent → DENY, otherwise → ALLOW.
 */
@Primary
@Component
public class TestOpaPolicyEngineClient implements OpaPolicyEngineClient {

    @Override
    public PolicyEvaluationResult evaluate(DecisionContext decisionContext) {
        long start = System.nanoTime();
        List<PolicyRuleResult> ruleResults = new ArrayList<>();

        if (decisionContext.getAction().getTargetResource() == null
                || decisionContext.getAction().getTargetResource().isBlank()) {
            ruleResults.add(PolicyRuleResult.builder()
                    .policyName("base-policy")
                    .ruleName("missing-resource")
                    .decision(DecisionType.DENY)
                    .reason("Action missing target resource")
                    .build());
        }

        if (decisionContext.getAction().getAgentId() == null
                || decisionContext.getAction().getAgentId().isBlank()) {
            ruleResults.add(PolicyRuleResult.builder()
                    .policyName("base-policy")
                    .ruleName("missing-agent")
                    .decision(DecisionType.DENY)
                    .reason("Agent is not identified")
                    .build());
        }

        if (decisionContext.getStateFacts() != null) {
            Object trustTier = decisionContext.getStateFacts().get("agentTrustTier");
            if ("TIER_3".equals(trustTier)) {
                ruleResults.add(PolicyRuleResult.builder()
                        .policyName("high-trust-policy")
                        .ruleName("high-trust-approval")
                        .decision(DecisionType.HUMAN_APPROVAL)
                        .reason("High trust tier requires human approval")
                        .build());
            }
        }

        if (ruleResults.isEmpty()) {
            ruleResults.add(PolicyRuleResult.builder()
                    .policyName("base-policy")
                    .ruleName("default-allow")
                    .decision(DecisionType.ALLOW)
                    .reason("Standard allow for verified low-risk action")
                    .build());
        }

        return PolicyEvaluationResult.builder()
                .ruleResults(ruleResults)
                .successful(true)
                .evaluationDurationNanos(System.nanoTime() - start)
                .build();
    }

    @Override
    public boolean isHealthy() {
        return true;
    }
}
