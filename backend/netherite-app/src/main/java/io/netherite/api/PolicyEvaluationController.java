package io.netherite.api;

import io.netherite.common.context.TenantContext;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionExplanation;
import io.netherite.common.model.DecisionType;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.normalizer.DefaultActionNormalizer;
import io.netherite.ingestion.spi.NormalizationResult;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.security.context.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Verification endpoint for Phase 4 policy evaluation.
 * Accepts an authenticated action request, normalizes it, and evaluates it through the policy engine.
 */
@RestController
@RequestMapping("/v1/actions")
@RequiredArgsConstructor
public class PolicyEvaluationController {

    private final DefaultActionNormalizer normalizer;
    private final PolicyEvaluationService policyEvaluationService;

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateAction(
            @Valid @RequestBody ActionIngestionRequest request) {

        TenantContext tenantContext = TenantContextHolder.getRequiredContext();

        NormalizationResult normalizationResult = normalizer.normalize(request, tenantContext);
        if (!normalizationResult.isSuccessful()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CANONICALIZATION_FAILED",
                    "details", normalizationResult.getValidationErrors()));
        }

        CanonicalAction action = normalizationResult.getCanonicalAction();
        PolicyEvaluationResult evalResult = policyEvaluationService.evaluate(action);

        DecisionType finalDecision = policyEvaluationService.resolvePrecedence(evalResult);
        DecisionExplanation explanation = policyEvaluationService.buildExplanation(finalDecision, evalResult);

        return ResponseEntity.ok(Map.of(
                "actionId", action.getActionId(),
                "decision", finalDecision.name(),
                "explanation", explanation,
                "evaluationDurationNanos", evalResult.getEvaluationDurationNanos(),
                "ruleCount", evalResult.getRuleResults().size()));
    }
}
