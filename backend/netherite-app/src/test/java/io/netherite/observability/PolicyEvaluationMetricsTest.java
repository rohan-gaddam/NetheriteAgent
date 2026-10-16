package io.netherite.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionType;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.service.DecisionContextService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS8 — policy evaluation observability.
 *
 * <p>Every OPA evaluation is recorded with a bounded outcome tag ({@code allow},
 * {@code deny}, {@code approval}, {@code error}) plus a duration timer, and the
 * fail-closed error path still increments the outcome counter before the exception
 * propagates.</p>
 */
class PolicyEvaluationMetricsTest {

    private static final CanonicalAction ACTION = CanonicalAction.builder()
            .actionId("act-ws8")
            .actionType("PAYMENT:REFUND")
            .targetResource("invoice:42")
            .parameters(Map.of())
            .userId("user-1")
            .agentId("agent-1")
            .organizationId("org-1")
            .timestamp(Instant.now())
            .build();

    private PolicyEvaluationService service(OpaPolicyEngineClient client, SimpleMeterRegistry registry) {
        return new PolicyEvaluationService(
                new DecisionContextService(List.of()), client, new NetheriteMetrics(registry));
    }

    private PolicyRuleResult rule(DecisionType decision) {
        return PolicyRuleResult.builder()
                .policyName("p-ws8")
                .ruleName("r-ws8")
                .decision(decision)
                .reason("unit stub")
                .build();
    }

    @Test
    void recordsAllowDecisionWithTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpaPolicyEngineClient client = mock(OpaPolicyEngineClient.class);
        when(client.evaluate(any())).thenReturn(PolicyEvaluationResult.builder()
                .successful(true)
                .evaluationDurationNanos(42)
                .ruleResults(List.of(rule(DecisionType.ALLOW)))
                .build());

        DecisionType decision = service(client, registry).evaluateDecision(ACTION);

        assertThat(decision).isEqualTo(DecisionType.ALLOW);
        assertThat(registry.get("netherite.opa.evaluations_total").tag("outcome", "allow").counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("netherite.opa.evaluation_duration_seconds").tag("outcome", "allow")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsDenyAndApprovalOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpaPolicyEngineClient client = mock(OpaPolicyEngineClient.class);
        when(client.evaluate(any())).thenReturn(PolicyEvaluationResult.builder()
                .successful(true)
                .ruleResults(List.of(rule(DecisionType.DENY)))
                .build());

        assertThat(service(client, registry).evaluateDecision(ACTION)).isEqualTo(DecisionType.DENY);
        assertThat(registry.get("netherite.opa.evaluations_total").tag("outcome", "deny").counter()
                .count()).isEqualTo(1);

        when(client.evaluate(any())).thenReturn(PolicyEvaluationResult.builder()
                .successful(true)
                .ruleResults(List.of(rule(DecisionType.HUMAN_APPROVAL)))
                .build());

        assertThat(service(client, registry).evaluateDecision(ACTION)).isEqualTo(DecisionType.HUMAN_APPROVAL);
        assertThat(registry.get("netherite.opa.evaluations_total").tag("outcome", "approval").counter()
                .count()).isEqualTo(1);
    }

    @Test
    void recordsErrorOutcomeOnFailClosedPath() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpaPolicyEngineClient client = mock(OpaPolicyEngineClient.class);
        when(client.evaluate(any())).thenReturn(PolicyEvaluationResult.builder()
                .successful(false)
                .errorMessage("opa unreachable")
                .build());

        assertThatThrownBy(() -> service(client, registry).evaluate(ACTION))
                .isInstanceOf(NetheriteException.class)
                .satisfies(ex -> assertThat(((NetheriteException) ex).getErrorCode())
                        .isEqualTo(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE));

        assertThat(registry.get("netherite.opa.evaluations_total").tag("outcome", "error").counter()
                .count()).isEqualTo(1);
    }
}