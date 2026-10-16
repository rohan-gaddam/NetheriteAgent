package io.netherite.ingestion.spi;

import io.netherite.common.context.TenantContext;
import io.netherite.ingestion.model.ActionIngestionRequest;

/**
 * Pluggable SPI for normalizing different agent payload formats into a validated CanonicalAction.
 */
public interface ActionNormalizer {

    /**
     * Determines whether this normalizer can process the given payload.
     */
    boolean supports(ActionIngestionRequest request);

    /**
     * Normalizes the request into a canonical action.
     */
    NormalizationResult normalize(ActionIngestionRequest request, TenantContext tenantContext);
}
