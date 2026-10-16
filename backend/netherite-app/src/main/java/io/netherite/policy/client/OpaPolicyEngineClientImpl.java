package io.netherite.policy.client;

import io.netherite.context.model.DecisionContext;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.observability.LogSanitizer;
import io.netherite.observability.ThrottledRate;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client implementation that evaluates decisions against an OPA (Open Policy Agent) instance.
 * Fails closed: if OPA is unreachable, evaluation is marked as unsuccessful.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "netherite.opa.policy-path")
public class OpaPolicyEngineClientImpl implements OpaPolicyEngineClient {

    private final RestTemplate restTemplate;
    private final String opaUrl;
    private final String policyPath;
    private final long timeoutMs;
    private final SsrfGuardValidator ssrfGuardValidator;

    /** Optional bearer token for the (authenticated) policy engine; blank means no header. */
    private final String opaToken;

    /** WS8 anti log-storm: at most one engine-failure line per 30s per node. */
    private static final ThrottledRate FAILURE_LOG_THROTTLE = new ThrottledRate(30_000_000_000L);

    public OpaPolicyEngineClientImpl(
            String opaUrl,
            String policyPath,
            long timeoutMs) {
        this(opaUrl, policyPath, timeoutMs, null, null);
    }

    public OpaPolicyEngineClientImpl(
            String opaUrl,
            String policyPath,
            long timeoutMs,
            String opaToken) {
        this(opaUrl, policyPath, timeoutMs, null, opaToken);
    }

    @Autowired
    public OpaPolicyEngineClientImpl(
            @Value("${netherite.opa.url}") String opaUrl,
            @Value("${netherite.opa.policy-path}") String policyPath,
            @Value("${netherite.opa.timeout-ms:200}") long timeoutMs,
            SsrfGuardValidator ssrfGuardValidator,
            @Value("${netherite.opa.token:}") String opaToken) {
        this.opaUrl = opaUrl;
        this.policyPath = policyPath;
        this.timeoutMs = timeoutMs;
        this.ssrfGuardValidator = ssrfGuardValidator;
        this.opaToken = opaToken;

        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(2000, timeoutMs));
        factory.setReadTimeout((int) timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (opaToken != null && !opaToken.isBlank()) {
            headers.setBearerAuth(opaToken);
        }
        return headers;
    }

    @Override
    public PolicyEvaluationResult evaluate(DecisionContext decisionContext) {
        long start = System.nanoTime();

        if (!isOpaEndpointPermitted()) {
            return PolicyEvaluationResult.builder()
                    .successful(false)
                    .errorMessage("OPA endpoint failed SSRF validation")
                    .evaluationDurationNanos(System.nanoTime() - start)
                    .build();
        }

        try {
            Map<String, Object> input = buildOpaInput(decisionContext);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("input", input), buildHeaders());

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    opaUrl + policyPath, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseOpaResponse(response.getBody(), start);
            }

            return PolicyEvaluationResult.builder()
                    .successful(false)
                    .errorMessage("OPA returned non-successful response")
                    .evaluationDurationNanos(System.nanoTime() - start)
                    .build();

        } catch (ResourceAccessException e) {
            if (FAILURE_LOG_THROTTLE.tryEmitOncePer("opa:unreachable")) {
                log.error("OPA engine unreachable: {}", LogSanitizer.clean(e.getMessage()));
            }
            return PolicyEvaluationResult.builder()
                    .successful(false)
                    .errorMessage("OPA engine unavailable: " + e.getMessage())
                    .evaluationDurationNanos(System.nanoTime() - start)
                    .build();
        } catch (Exception e) {
            if (FAILURE_LOG_THROTTLE.tryEmitOncePer("opa:evaluation")) {
                log.error("OPA evaluation failed: {}", LogSanitizer.clean(e.getMessage()));
            }
            return PolicyEvaluationResult.builder()
                    .successful(false)
                    .errorMessage("Policy evaluation failed: " + e.getMessage())
                    .evaluationDurationNanos(System.nanoTime() - start)
                    .build();
        }
    }

    @Override
    public boolean isHealthy() {
        if (!isOpaEndpointPermitted()) {
            return false;
        }
        try {
            HttpEntity<Void> request = new HttpEntity<>(buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl + "/health", org.springframework.http.HttpMethod.GET, request, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOpaEndpointPermitted() {
        if (ssrfGuardValidator == null) {
            return true;
        }
        try {
            URI opaUri = URI.create(opaUrl);
            if (ssrfGuardValidator.isUriPermittedForOperatorConfigured(opaUri)) {
                return true;
            }
            if (FAILURE_LOG_THROTTLE.tryEmitOncePer("opa:ssrf")) {
                log.error("OPA endpoint rejected by SSRF guard: {}", opaUrl);
            }
            return false;
        } catch (Exception e) {
            if (FAILURE_LOG_THROTTLE.tryEmitOncePer("opa:uri")) {
                log.error("OPA endpoint URI invalid: {}", opaUrl, e);
            }
            return false;
        }
    }

    private Map<String, Object> buildOpaInput(DecisionContext context) {
        Map<String, Object> action = new HashMap<>();
        action.put("id", context.getAction().getActionId());
        action.put("type", context.getAction().getActionType());
        action.put("resource", context.getAction().getTargetResource());
        action.put("parameters", context.getAction().getParameters());
        action.put("userId", context.getAction().getUserId());

        Map<String, Object> agent = new HashMap<>();
        agent.put("id", context.getAction().getAgentId());
        agent.put("organizationId", context.getAction().getOrganizationId());

        Map<String, Object> input = new HashMap<>();
        input.put("action", action);
        input.put("agent", agent);
        input.put("context", context.getStateFacts() != null ? context.getStateFacts() : Map.of());
        input.put("businessFacts", context.getBusinessFacts() != null ? context.getBusinessFacts() : Map.of());
        input.put("securitySignals", context.getSecuritySignals() != null ? context.getSecuritySignals() : Map.of());

        return input;
    }

    @SuppressWarnings("unchecked")
    private PolicyEvaluationResult parseOpaResponse(Map<String, Object> responseBody, long start) {
        Object result = responseBody.get("result");
        if (result == null) {
            return PolicyEvaluationResult.builder()
                    .successful(false)
                    .errorMessage("OPA returned null result")
                    .evaluationDurationNanos(System.nanoTime() - start)
                    .build();
        }

        Map<String, Object> evaluationOutput;
        if (result instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) result;
            evaluationOutput = resultMap;
        } else {
            return PolicyEvaluationResult.builder()
                    .successful(false)
                    .errorMessage("OPA result is not a map")
                    .evaluationDurationNanos(System.nanoTime() - start)
                    .build();
        }

        String decision = (String) evaluationOutput.get("decision");
        List<String> denyReasons = toStringList(evaluationOutput.get("denyReasons"));
        List<String> approvalReasons = toStringList(evaluationOutput.get("approvalReasons"));
        List<String> allowReasons = toStringList(evaluationOutput.get("allowReasons"));

        List<PolicyRuleResult> ruleResults = new ArrayList<>();

        for (String reason : denyReasons) {
            ruleResults.add(PolicyRuleResult.builder()
                    .policyName("base-policy")
                    .decision(io.netherite.common.model.DecisionType.DENY)
                    .reason(reason)
                    .build());
        }
        for (String reason : approvalReasons) {
            ruleResults.add(PolicyRuleResult.builder()
                    .policyName("base-policy")
                    .decision(io.netherite.common.model.DecisionType.HUMAN_APPROVAL)
                    .reason(reason)
                    .build());
        }
        for (String reason : allowReasons) {
            ruleResults.add(PolicyRuleResult.builder()
                    .policyName("base-policy")
                    .decision(io.netherite.common.model.DecisionType.ALLOW)
                    .reason(reason)
                    .build());
        }

        return PolicyEvaluationResult.builder()
                .ruleResults(ruleResults)
                .successful(true)
                .evaluationDurationNanos(System.nanoTime() - start)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List) {
            return ((List<Object>) obj).stream()
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }
}
