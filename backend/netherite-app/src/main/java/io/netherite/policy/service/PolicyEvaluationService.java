package io.netherite.policy.service;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionExplanation;
import io.netherite.common.model.DecisionType;
import io.netherite.context.model.DecisionContext;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates deterministic policy evaluation: builds DecisionContext → evaluates via OPA → resolves precedence.
 * Fail-closed: OPA unavailability results in ERR_SYSTEM_UNAVAILABLE, NOT ALLOW.
 * No LLM is involved in any authorization decision.
 */
@Slf4j
@Service
public class PolicyEvaluationService {

    private final DecisionContextService decisionContextService;
    private final OpaPolicyEngineClient opaPolicyEngineClient;
    private final NetheriteMetrics metrics;

    /** Preserves the direct-construction contract used by policy unit tests (no registry). */
    public PolicyEvaluationService(DecisionContextService decisionContextService,
                                   OpaPolicyEngineClient opaPolicyEngineClient) {
        this(decisionContextService, opaPolicyEngineClient, null);
    }

    @Autowired
    public PolicyEvaluationService(DecisionContextService decisionContextService,
                                   OpaPolicyEngineClient opaPolicyEngineClient,
                                   NetheriteMetrics metrics) {
        this.decisionContextService = decisionContextService;
        this.opaPolicyEngineClient = opaPolicyEngineClient;
        this.metrics = metrics != null ? metrics : NetheriteMetrics.NOOP;
    }

    /**
     * Evaluates the given CanonicalAction against the tenant's policies.
     * Returns a PolicyEvaluationResult containing the final decision, rule results, and explanation.
     *
     * @throws NetheriteException if the policy engine is unavailable (fail-closed)
     */
    public PolicyEvaluationResult evaluate(CanonicalAction action) {
        DecisionContext context = decisionContextService.buildDecisionContext(action);

        long start = System.nanoTime();
        PolicyEvaluationResult rawResult = opaPolicyEngineClient.evaluate(context);
        long elapsedNanos = System.nanoTime() - start;

        if (!rawResult.isSuccessful()) {
            metrics.opaEvaluation("error", elapsedNanos);
            throw new NetheriteException(
                    SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Policy evaluation infrastructure failure: " + rawResult.getErrorMessage());
        }

        DecisionType finalDecision = resolvePrecedence(rawResult);
        metrics.opaEvaluation(outcome(finalDecision), elapsedNanos);

        DecisionExplanation explanation = buildExplanation(finalDecision, rawResult);

        return PolicyEvaluationResult.builder()
                .ruleResults(rawResult.getRuleResults())
                .evaluationDurationNanos(rawResult.getEvaluationDurationNanos())
                .successful(true)
                .errorMessage(null)
                .build();
    }

    /**
     * Evaluates and returns the final decision type directly.
     */
    public DecisionType evaluateDecision(CanonicalAction action) {
        DecisionContext context = decisionContextService.buildDecisionContext(action);

        long start = System.nanoTime();
        PolicyEvaluationResult rawResult = opaPolicyEngineClient.evaluate(context);
        long elapsedNanos = System.nanoTime() - start;

        if (!rawResult.isSuccessful()) {
            metrics.opaEvaluation("error", elapsedNanos);
            throw new NetheriteException(
                    SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Policy evaluation infrastructure failure: " + rawResult.getErrorMessage());
        }

        DecisionType finalDecision = resolvePrecedence(rawResult);
        metrics.opaEvaluation(outcome(finalDecision), elapsedNanos);
        return finalDecision;
    }

    /** Bounded outcome tag for the OPA metrics path. */
    private static String outcome(DecisionType decision) {
        return switch (decision) {
            case ALLOW -> "allow";
            case HUMAN_APPROVAL -> "approval";
            case DENY -> "deny";
        };
    }

    /**
     * Resolves the final decision using the locked precedence: DENY > HUMAN_APPROVAL > ALLOW.
     */
    public DecisionType resolvePrecedence(PolicyEvaluationResult result) {
        if (result.getRuleResults() == null || result.getRuleResults().isEmpty()) {
            return DecisionType.DENY;
        }

        boolean hasDeny = result.getRuleResults().stream()
                .anyMatch(r -> r.getDecision() == DecisionType.DENY);
        boolean hasApproval = result.getRuleResults().stream()
                .anyMatch(r -> r.getDecision() == DecisionType.HUMAN_APPROVAL);
        boolean hasAllow = result.getRuleResults().stream()
                .anyMatch(r -> r.getDecision() == DecisionType.ALLOW);

        if (hasDeny) return DecisionType.DENY;
        if (hasApproval) return DecisionType.HUMAN_APPROVAL;
        if (hasAllow) return DecisionType.ALLOW;

        return DecisionType.DENY;
    }

    public DecisionExplanation buildExplanation(DecisionType finalDecision, PolicyEvaluationResult result) {
        List<String> policyNames = result.getRuleResults().stream()
                .map(PolicyRuleResult::getPolicyName)
                .distinct()
                .collect(Collectors.toList());

        List<String> evaluations = result.getRuleResults().stream()
                .map(r -> r.getPolicyName() + "/" + r.getRuleName() + " → " + r.getDecision()
                        + (r.getReason() != null ? " (" + r.getReason() + ")" : ""))
                .collect(Collectors.toList());

        String primaryReason;
        if (finalDecision == DecisionType.DENY) {
            primaryReason = result.getRuleResults().stream()
                    .filter(r -> r.getDecision() == DecisionType.DENY)
                    .map(PolicyRuleResult::getReason)
                    .findFirst()
                    .orElse("Policy denied the action");
        } else if (finalDecision == DecisionType.HUMAN_APPROVAL) {
            primaryReason = result.getRuleResults().stream()
                    .filter(r -> r.getDecision() == DecisionType.HUMAN_APPROVAL)
                    .map(PolicyRuleResult::getReason)
                    .findFirst()
                    .orElse("Action requires human approval");
        } else {
            primaryReason = result.getRuleResults().stream()
                    .filter(r -> r.getDecision() == DecisionType.ALLOW)
                    .map(PolicyRuleResult::getReason)
                    .findFirst()
                    .orElse("Action allowed by policy");
        }

        return DecisionExplanation.builder()
                .finalDecision(finalDecision)
                .primaryReason(primaryReason)
                .contributingPolicies(policyNames)
                .ruleEvaluations(evaluations)
                .resolutionRule("DENY > HUMAN_APPROVAL > ALLOW")
                .build();
    }
}
