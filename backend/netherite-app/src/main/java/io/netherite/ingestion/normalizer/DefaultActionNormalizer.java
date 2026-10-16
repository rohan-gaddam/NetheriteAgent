package io.netherite.ingestion.normalizer;

import io.netherite.common.context.TenantContext;
import io.netherite.common.model.CanonicalAction;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.spi.ActionNormalizer;
import io.netherite.ingestion.spi.NormalizationResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default deterministic normalizer that converts a standard ActionIngestionRequest
 * into a CanonicalAction using trusted TenantContext for identity.
 *
 * This normalizer does NOT make authorization decisions.
 * It does NOT trust client-supplied tenant/agent identity.
 */
@Component
public class DefaultActionNormalizer implements ActionNormalizer {

    @Override
    public boolean supports(ActionIngestionRequest request) {
        return request != null;
    }

    @Override
    public NormalizationResult normalize(ActionIngestionRequest request, TenantContext tenantContext) {
        List<String> errors = validate(request, tenantContext);
        if (!errors.isEmpty()) {
            return NormalizationResult.builder()
                    .successful(false)
                    .validationErrors(errors)
                    .build();
        }

        String actionId = UUID.randomUUID().toString();
        Map<String, Object> parameters = request.getParameters() != null
                ? new HashMap<>(request.getParameters())
                : new HashMap<>();

        String userId = null;
        if (request.getUserContext() != null) {
            Object extractedUserId = request.getUserContext().get("userId");
            if (extractedUserId != null) {
                userId = extractedUserId.toString();
            }
        }

        CanonicalAction canonicalAction = CanonicalAction.builder()
                .actionId(actionId)
                .organizationId(tenantContext.getOrganizationId())
                .agentId(tenantContext.getAgentId())
                .userId(userId)
                .actionType(request.getAction().trim().toUpperCase())
                .targetResource(request.getResource().trim().toUpperCase())
                .parameters(parameters)
                .timestamp(Instant.now())
                .build();

        return NormalizationResult.builder()
                .successful(true)
                .canonicalAction(canonicalAction)
                .validationErrors(Collections.emptyList())
                .requiresSemanticFallback(false)
                .build();
    }

    private List<String> validate(ActionIngestionRequest request, TenantContext tenantContext) {
        List<String> errors = new ArrayList<>();

        if (request.getAction() == null || request.getAction().isBlank()) {
            errors.add("action is required");
        }
        if (request.getResource() == null || request.getResource().isBlank()) {
            errors.add("resource is required");
        }
        if (tenantContext == null) {
            errors.add("tenant context is required");
        } else {
            if (tenantContext.getOrganizationId() == null || tenantContext.getOrganizationId().isBlank()) {
                errors.add("authenticated organization identity is required");
            }
            if (tenantContext.getAgentId() == null || tenantContext.getAgentId().isBlank()) {
                errors.add("authenticated agent identity is required");
            }
        }

        return errors;
    }
}
