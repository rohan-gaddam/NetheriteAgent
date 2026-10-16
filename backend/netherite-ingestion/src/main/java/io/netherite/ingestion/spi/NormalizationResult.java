package io.netherite.ingestion.spi;

import io.netherite.common.model.CanonicalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of normalizer processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizationResult {
    private boolean successful;
    private CanonicalAction canonicalAction;
    private List<String> validationErrors;
    private boolean requiresSemanticFallback;
}
