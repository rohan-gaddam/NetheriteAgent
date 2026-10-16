package io.netherite.ingestion.model;

import io.netherite.common.model.CanonicalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Outcome of promoting an untrusted {@code SemanticExtractionCandidate} into a
 * validated {@link CanonicalAction}.
 *
 * <p>When {@link #successful} is {@code false}, {@link #canonicalAction} is
 * {@code null} and {@link #validationErrors} explains why. No candidate-derived value
 * (including raw model output) is ever echoed into the errors.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticActionPromotionResult {
    private boolean successful;
    private CanonicalAction canonicalAction;
    private List<String> validationErrors;
    private boolean parametersSchemaEnforced;
}
