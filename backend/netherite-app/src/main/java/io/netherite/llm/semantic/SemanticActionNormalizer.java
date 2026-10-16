package io.netherite.llm.semantic;

import io.netherite.common.context.TenantContext;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.model.SemanticActionPromotionResult;
import io.netherite.ingestion.promotion.SemanticActionPromoter;
import io.netherite.ingestion.spi.ActionNormalizer;
import io.netherite.ingestion.spi.NormalizationResult;
import io.netherite.llm.model.SemanticExtractionCandidate;
import io.netherite.llm.spi.LlmProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Semantic {@link ActionNormalizer} that engages the configured {@link LlmProvider} only
 * when a request carries natural-language input ({@code rawPrompt}) instead of a fully
 * structured {@code action}/{@code resource} pair.
 *
 * <p>It is the semantic entry point into the <b>existing</b> ingestion path: it produces
 * exactly one {@link NormalizationResult}, and any promoted {@code CanonicalAction} still
 * traverses the unchanged OPA / approval / idempotency / execution / audit pipeline. The
 * LLM never authorizes anything — the deterministic {@link SemanticActionPromoter} remains
 * the sole gate, and every failure (no provider, provider error, unpromotable candidate)
 * is fail-closed.</p>
 *
 * <p>Ordered first so it is selected ahead of the always-supporting
 * {@code DefaultActionNormalizer} for prompt-only requests.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SemanticActionNormalizer implements ActionNormalizer {

    private final ObjectProvider<LlmProvider> llmProviders;
    private final SemanticActionPromoter promoter;

    public SemanticActionNormalizer(ObjectProvider<LlmProvider> llmProviders,
                                    SemanticActionPromoter promoter) {
        this.llmProviders = llmProviders;
        this.promoter = promoter;
    }

    @Override
    public boolean supports(ActionIngestionRequest request) {
        if (request == null) {
            return false;
        }
        boolean hasPrompt = request.getRawPrompt() != null && !request.getRawPrompt().isBlank();
        if (!hasPrompt) {
            return false;
        }
        boolean fullyStructured = isPresent(request.getAction()) && isPresent(request.getResource());
        return !fullyStructured;
    }

    @Override
    public NormalizationResult normalize(ActionIngestionRequest request, TenantContext tenantContext) {
        if (request == null || request.getRawPrompt() == null || request.getRawPrompt().isBlank()) {
            return failure("a natural-language prompt is required for semantic interpretation");
        }

        LlmProvider provider = llmProviders.getIfAvailable();
        if (provider == null || !provider.isAvailable()) {
            return failure("semantic interpretation is not available: no LLM provider is configured");
        }

        String organizationId = tenantContext != null ? tenantContext.getOrganizationId() : null;

        Optional<SemanticExtractionCandidate> candidate;
        try {
            candidate = provider.extractCandidateAction(request.getRawPrompt(), organizationId);
        } catch (RuntimeException ex) {
            log.warn("LLM provider failed during semantic interpretation; failing closed");
            return failure("semantic interpretation failed; failing closed");
        }

        if (candidate == null || candidate.isEmpty()) {
            return failure("semantic interpretation did not produce a usable candidate action");
        }

        SemanticActionPromotionResult promotion;
        try {
            promotion = promoter.promote(candidate.get(), tenantContext);
        } catch (RuntimeException ex) {
            log.warn("Semantic promotion failed; failing closed");
            return failure("semantic promotion failed; failing closed");
        }

        if (promotion == null || !promotion.isSuccessful()) {
            List<String> errors = promotion == null ? List.of() : promotion.getValidationErrors();
            return failure(errors);
        }

        return NormalizationResult.builder()
                .successful(true)
                .canonicalAction(promotion.getCanonicalAction())
                .validationErrors(List.of())
                .requiresSemanticFallback(true)
                .build();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static NormalizationResult failure(String message) {
        return failure(List.of(message));
    }

    private static NormalizationResult failure(List<String> errors) {
        return NormalizationResult.builder()
                .successful(false)
                .canonicalAction(null)
                .validationErrors(errors == null || errors.isEmpty()
                        ? List.of("candidate could not be promoted")
                        : errors)
                .requiresSemanticFallback(false)
                .build();
    }
}
