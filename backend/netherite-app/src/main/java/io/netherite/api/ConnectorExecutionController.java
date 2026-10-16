package io.netherite.api;

import io.netherite.api.model.ConnectorExecutionApiRequest;
import io.netherite.api.model.ConnectorExecutionApiResponse;
import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.model.CanonicalAction;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.normalizer.DefaultActionNormalizer;
import io.netherite.ingestion.spi.NormalizationResult;
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
 * Phase 6 execution endpoint.
 * Delegates to ConnectorExecutionService which handles the full chain:
 * policy evaluation -> DENY/HUMAN_APPROVAL/ALLOW -> idempotency -> lock -> routing -> binding -> SSRF -> connector -> audit.
 */
@RestController
@RequestMapping("/v1/actions")
@RequiredArgsConstructor
public class ConnectorExecutionController {

    private final DefaultActionNormalizer normalizer;
    private final ConnectorExecutionService connectorExecutionService;

    @PostMapping("/execute")
    public ResponseEntity<?> executeAction(
            @Valid @RequestBody ConnectorExecutionApiRequest request) {

        TenantContext tenantContext = TenantContextHolder.getRequiredContext();

        ActionIngestionRequest ingestionRequest = ActionIngestionRequest.builder()
                .action(request.getAction())
                .resource(request.getResource())
                .parameters(request.getParameters())
                .build();

        NormalizationResult normalizationResult = normalizer.normalize(ingestionRequest, tenantContext);
        if (!normalizationResult.isSuccessful()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CANONICALIZATION_FAILED",
                    "details", normalizationResult.getValidationErrors()));
        }

        CanonicalAction action = normalizationResult.getCanonicalAction();

        try {
            ConnectorExecutionResult result = connectorExecutionService.execute(action);

            ConnectorExecutionApiResponse response = ConnectorExecutionApiResponse.builder()
                    .executionId(action.getActionId())
                    .successful(result.isSuccessful())
                    .statusCode(result.getStatusCode())
                    .responseBody(result.getResponseBody())
                    .executionDurationMillis(result.getExecutionDurationMillis())
                    .errorMessage(result.getErrorMessage())
                    .resource(request.getResource())
                    .connectorType(null)
                    .build();

            if (result.isSuccessful()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(result.getStatusCode() >= 400 ? result.getStatusCode() : 502)
                        .body(response);
            }

        } catch (NetheriteException e) {
            String details = e.getDetails();
            if (details != null && details.contains("Ticket ID:")) {
                String ticketId = details.substring(details.indexOf("Ticket ID:") + "Ticket ID:".length()).trim();
                return ResponseEntity.status(202).body(Map.of(
                        "status", "PENDING_APPROVAL",
                        "ticketId", ticketId,
                        "message", details));
            }
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                    "error", e.getErrorCode().name(),
                    "message", e.getDetails()));
        }
    }
}
