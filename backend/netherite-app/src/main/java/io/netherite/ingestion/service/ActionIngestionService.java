package io.netherite.ingestion.service;

import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.model.ActionIngestionResponse;
import io.netherite.ingestion.spi.ActionNormalizer;
import io.netherite.ingestion.spi.NormalizationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates action ingestion: validation → normalization → CanonicalAction.
 * This service does NOT perform authorization. It establishes the trusted
 * canonical representation for future policy evaluation.
 */
@Service
@RequiredArgsConstructor
public class ActionIngestionService {

    private final List<ActionNormalizer> actionNormalizers;

    public ActionIngestionResponse ingest(ActionIngestionRequest request, TenantContext tenantContext) {
        if (request == null) {
            throw new NetheriteException(SystemErrorCode.ERR_CANONICALIZATION_FAILED, "Request body is required");
        }

        ActionNormalizer normalizer = actionNormalizers.stream()
                .filter(n -> n.supports(request))
                .findFirst()
                .orElseThrow(() -> new NetheriteException(
                        SystemErrorCode.ERR_CANONICALIZATION_FAILED,
                        "No normalizer available for the given request format"));

        NormalizationResult result = normalizer.normalize(request, tenantContext);

        if (!result.isSuccessful()) {
            String errorDetail = String.join("; ", result.getValidationErrors());
            throw new NetheriteException(SystemErrorCode.ERR_CANONICALIZATION_FAILED, errorDetail);
        }

        return ActionIngestionResponse.builder()
                .actionId(result.getCanonicalAction().getActionId())
                .status("INGESTED")
                .build();
    }
}
