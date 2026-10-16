package io.netherite.api;

import io.netherite.api.model.GatewayActionRequest;
import io.netherite.api.model.GatewayActionResponse;
import io.netherite.api.model.GatewayIntegrationInfo;
import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.model.CanonicalAction;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.spi.ActionNormalizer;
import io.netherite.ingestion.spi.NormalizationResult;
import io.netherite.security.context.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Production agent gateway.
 * External AI agents route their actions through this endpoint.
 *
 * Authentication: API key via X-Netherite-Agent-Key header (handled by AgentAuthenticationFilter).
 * Identity: Organization and agent come from the authenticated key, NOT from the request body.
 * Resource: The resource field identifies a registered resource by name - NOT a URL.
 * Pipeline: Feeds into the existing ConnectorExecutionService (policy → lock → routing → binding → SSRF → connector → audit).
 */
@Slf4j
@RestController
@RequestMapping("/v1/gateway")
@RequiredArgsConstructor
public class AgentGatewayController {

    private final List<ActionNormalizer> normalizers;
    private final ConnectorExecutionService connectorExecutionService;

    /**
     * Execute an agent action through the full NetheriteAgent security pipeline.
     *
     * Flow:
     *   1. AgentAuthenticationFilter authenticates API key → trusted TenantContext
     *   2. Normalize request into CanonicalAction (trusted identity from TenantContext)
     *   3. ConnectorExecutionService.execute() → full 13-step chain:
     *      - Policy evaluation (OPA) → ALLOW / DENY / HUMAN_APPROVAL
     *      - Idempotency check
     *      - Distributed lock
     *      - Resource route resolution (from registered ResourceEntity, NOT from client)
     *      - Binding authorization
     *      - SSRF validation
     *      - Connector execution (HTTP/GraphQL/gRPC)
     *      - Audit recording
     *   4. Return standardized GatewayActionResponse
     */
    @PostMapping("/execute")
    public ResponseEntity<GatewayActionResponse> executeAction(
            @Valid @RequestBody GatewayActionRequest request) {

        TenantContext tenantContext = TenantContextHolder.getRequiredContext();

        // Normalize to CanonicalAction (trusted identity from TenantContext, NOT from client).
        // A fully structured request uses the deterministic normalizer; a natural-language
        // rawPrompt is routed to the semantic normalizer, whose candidate is still promoted
        // deterministically before it can reach the execution pipeline.
        ActionIngestionRequest ingestionRequest = ActionIngestionRequest.builder()
                .action(request.getAction())
                .resource(request.getResource())
                .parameters(request.getParameters())
                .rawPrompt(request.getRawPrompt())
                .build();

        ActionNormalizer normalizer = normalizers.stream()
                .filter(n -> n.supports(ingestionRequest))
                .findFirst()
                .orElse(null);
        if (normalizer == null) {
            return ResponseEntity.badRequest().body(
                    GatewayActionResponse.validationError(
                            "No normalizer is available for the given request format: supply either "
                                    + "'action' and 'resource', or a natural-language 'rawPrompt'."));
        }

        NormalizationResult normalizationResult = normalizer.normalize(ingestionRequest, tenantContext);
        if (!normalizationResult.isSuccessful()) {
            return ResponseEntity.badRequest().body(
                    GatewayActionResponse.validationError(
                            "Canonicalization failed: " + String.join(", ", normalizationResult.getValidationErrors())));
        }

        CanonicalAction action = normalizationResult.getCanonicalAction();
        String responseAction = request.getAction() != null ? request.getAction() : action.getActionType();
        String responseResource = request.getResource() != null ? request.getResource() : action.getTargetResource();

        // Set idempotency key if provided
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            action.setIdempotencyKey(request.getIdempotencyKey());
        }

        try {
            // Execute through the full existing 13-step security pipeline
            ConnectorExecutionResult result = connectorExecutionService.execute(action);

            if (result.isSuccessful()) {
                return ResponseEntity.ok(GatewayActionResponse.executed(
                        action.getActionId(),
                        responseAction,
                        responseResource,
                        result.getStatusCode(),
                        result.getResponseBody(),
                        result.getExecutionDurationMillis()));
            } else {
                return ResponseEntity.status(result.getStatusCode() >= 400 ? result.getStatusCode() : 502)
                        .body(GatewayActionResponse.executionError(
                                action.getActionId(),
                                responseAction,
                                responseResource,
                                result.getErrorMessage()));
            }

        } catch (NetheriteException e) {
            String details = e.getDetails();

            // HUMAN_APPROVAL → return approval ticket
            if (details != null && details.contains("Ticket ID:")) {
                String ticketId = details.substring(details.indexOf("Ticket ID:") + "Ticket ID:".length()).trim();
                return ResponseEntity.status(202).body(GatewayActionResponse.pendingApproval(
                        action.getActionId(),
                        responseAction,
                        responseResource,
                        ticketId,
                        "Action requires human approval"));
            }

            // DENY → return denial
            if (details != null && details.contains("DENY")) {
                return ResponseEntity.status(403).body(GatewayActionResponse.denied(
                        action.getActionId(),
                        responseAction,
                        responseResource));
            }

            // DUPLICATE CONCURRENT → return 409 Conflict
            if (details != null && details.contains("Duplicate")) {
                return ResponseEntity.status(409).body(GatewayActionResponse.executionError(
                        action.getActionId(),
                        responseAction,
                        responseResource,
                        "Duplicate request already processing"));
            }

            // Other errors
            String message = details != null ? details : e.getErrorCode().getDefaultMessage();
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(
                    GatewayActionResponse.executionError(
                            action.getActionId(),
                            responseAction,
                            responseResource,
                            message));
        }
    }

    /**
     * Returns integration information for configuring AI agents to use the gateway.
     * Does NOT expose secrets or internal implementation details.
     */
    @GetMapping("/integration")
    public ResponseEntity<GatewayIntegrationInfo> getIntegrationInfo() {
        return ResponseEntity.ok(GatewayIntegrationInfo.builder()
                .gatewayEndpoint("/v1/gateway/execute")
                .authenticationMethod("API_KEY")
                .authenticationHeader("X-Netherite-Agent-Key")
                .requestFormat("{\"action\": \"ACTION_TYPE\", \"resource\": \"RESOURCE_NAME\", \"parameters\": {...}, \"idempotencyKey\": \"optional-idem-key\"}")
                .documentation("https://docs.netherite.dev/gateway")
                .build());
    }
}
