package io.netherite.policy;

import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionType;
import io.netherite.context.model.DecisionContext;
import io.netherite.policy.client.OpaPolicyEngineClientImpl;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the REAL {@code OpaPolicyEngineClientImpl} against a live Open
 * Policy Agent server (local OPA pinned at 0.68.0, policies from
 * infrastructure/docker/opa/policies). This proves the on-the-wire input mapping and
 * response parsing that the control plane uses in production.
 *
 * Skipped by default; run with {@code -Dnetherite.realopa-it=true} when a policy engine
 * is listening on localhost:8181 (the WS4 verification harness starts one).
 */
@EnabledIfSystemProperty(named = "netherite.realopa-it", matches = "true")
class RealOpaContractIT {

    private final OpaPolicyEngineClient client =
            new OpaPolicyEngineClientImpl(
                    System.getenv().getOrDefault("REALOPA_URL", "http://localhost:8181"),
                    "/v1/data/netherite/authz/evaluation_output",
                    5000);

    @Test
    void lowRiskActionMapsToAllow() {
        PolicyEvaluationResult result = client.evaluate(context("act-1", "invoice:123", 10));

        assertThat(result.isSuccessful()).isTrue();
        DecisionType decision = resolvePrecedence(result);
        assertThat(decision).isEqualTo(DecisionType.ALLOW);
    }

    @Test
    void highRiskActionMapsToHumanApproval() {
        PolicyEvaluationResult result = client.evaluate(context("act-2", "invoice:123", 70));

        assertThat(result.isSuccessful()).isTrue();
        DecisionType decision = resolvePrecedence(result);
        assertThat(decision).isEqualTo(DecisionType.HUMAN_APPROVAL);
    }

    @Test
    void missingResourceMapsToDeny() {
        PolicyEvaluationResult result = client.evaluate(context("act-3", "", 10));

        assertThat(result.isSuccessful()).isTrue();
        DecisionType decision = resolvePrecedence(result);
        assertThat(decision).isEqualTo(DecisionType.DENY);
    }

    @Test
    void unreachableOpaFailsClosedWithUnsuccessfulResult() {
        OpaPolicyEngineClient unreachable =
                new OpaPolicyEngineClientImpl("http://localhost:59999", "/v1/data/netherite/authz/evaluation_output", 500);

        PolicyEvaluationResult result = unreachable.evaluate(context("act-4", "invoice:123", 10));

        assertThat(result.isSuccessful()).isFalse();
    }

    private static DecisionContext context(String actionId, String resource, int riskScore) {
        return DecisionContext.builder()
                .contextId("ctx-" + actionId)
                .action(CanonicalAction.builder()
                        .actionId(actionId)
                        .actionType("GENERIC")
                        .targetResource(resource)
                        .parameters(Map.of())
                        .userId("user-1")
                        .agentId("agent-1")
                        .organizationId("org-1")
                        .timestamp(Instant.now())
                        .build())
                .businessFacts(Map.of())
                .stateFacts(Map.of("riskScore", riskScore))
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();
    }

    private static DecisionType resolvePrecedence(PolicyEvaluationResult result) {
        List<PolicyRuleResult> rules = result.getRuleResults();
        if (rules == null || rules.isEmpty()) {
            return DecisionType.DENY;
        }
        boolean deny = rules.stream().anyMatch(r -> r.getDecision() == DecisionType.DENY);
        boolean approval = rules.stream().anyMatch(r -> r.getDecision() == DecisionType.HUMAN_APPROVAL);
        boolean allow = rules.stream().anyMatch(r -> r.getDecision() == DecisionType.ALLOW);
        if (deny) return DecisionType.DENY;
        if (approval) return DecisionType.HUMAN_APPROVAL;
        if (allow) return DecisionType.ALLOW;
        return DecisionType.DENY;
    }
}