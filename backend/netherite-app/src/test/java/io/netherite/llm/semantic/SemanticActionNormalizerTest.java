package io.netherite.llm.semantic;

import io.netherite.common.context.TenantContext;
import io.netherite.common.model.CanonicalAction;
import io.netherite.ingestion.model.ActionIngestionRequest;
import io.netherite.ingestion.model.SemanticActionPromotionResult;
import io.netherite.ingestion.promotion.SemanticActionPromoter;
import io.netherite.ingestion.spi.NormalizationResult;
import io.netherite.llm.model.SemanticExtractionCandidate;
import io.netherite.llm.spi.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticActionNormalizerTest {

    private ObjectProvider<LlmProvider> llmProviders;
    private LlmProvider provider;
    private SemanticActionPromoter promoter;
    private SemanticActionNormalizer normalizer;

    private final TenantContext tenantContext = TenantContext.builder()
            .organizationId("org-1")
            .agentId("agent-1")
            .principalType("AGENT")
            .build();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        llmProviders = mock(ObjectProvider.class);
        provider = mock(LlmProvider.class);
        promoter = mock(SemanticActionPromoter.class);
        normalizer = new SemanticActionNormalizer(llmProviders, promoter);
    }

    private ActionIngestionRequest promptOnly(String prompt) {
        return ActionIngestionRequest.builder().rawPrompt(prompt).build();
    }

    @Test
    void supportsFalseForFullyStructuredRequest() {
        ActionIngestionRequest request = ActionIngestionRequest.builder()
                .action("PAYMENT:REFUND").resource("PAYMENT_API").rawPrompt("ignore me").build();
        assertThat(normalizer.supports(request)).isFalse();
    }

    @Test
    void supportsFalseWithoutPrompt() {
        assertThat(normalizer.supports(ActionIngestionRequest.builder().action("A").build())).isFalse();
        assertThat(normalizer.supports(null)).isFalse();
    }

    @Test
    void supportsTrueForPromptOnlyRequest() {
        assertThat(normalizer.supports(promptOnly("refund the payment"))).isTrue();
    }

    @Test
    void supportsTrueForPromptWithPartialStructure() {
        ActionIngestionRequest request = ActionIngestionRequest.builder()
                .action("PAYMENT:REFUND").rawPrompt("refund the payment").build();
        assertThat(normalizer.supports(request)).isTrue();
    }

    @Test
    void failsClosedWhenNoProviderBeanExists() {
        when(llmProviders.getIfAvailable()).thenReturn(null);

        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), tenantContext);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getValidationErrors()).anyMatch(e -> e.contains("no LLM provider"));
        verify(promoter, never()).promote(any(), any());
    }

    @Test
    void failsClosedWhenProviderUnavailable() {
        when(llmProviders.getIfAvailable()).thenReturn(provider);
        when(provider.isAvailable()).thenReturn(false);

        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), tenantContext);

        assertThat(result.isSuccessful()).isFalse();
        verify(provider, never()).extractCandidateAction(any(), any());
    }

    @Test
    void failsClosedWhenProviderReturnsEmpty() {
        when(llmProviders.getIfAvailable()).thenReturn(provider);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.extractCandidateAction("refund the payment", "org-1")).thenReturn(Optional.empty());

        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), tenantContext);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getValidationErrors()).anyMatch(e -> e.contains("did not produce"));
        verify(promoter, never()).promote(any(), any());
    }

    @Test
    void failsClosedWhenProviderThrows() {
        when(llmProviders.getIfAvailable()).thenReturn(provider);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.extractCandidateAction(any(), any())).thenThrow(new RuntimeException("boom"));

        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), tenantContext);

        assertThat(result.isSuccessful()).isFalse();
        verify(promoter, never()).promote(any(), any());
    }

    @Test
    void failsClosedWhenPromotionFails() {
        SemanticExtractionCandidate candidate = SemanticExtractionCandidate.builder()
                .candidateActionType("PAYMENT:REFUND")
                .candidateTargetResource("PAYMENT_API")
                .build();
        when(llmProviders.getIfAvailable()).thenReturn(provider);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.extractCandidateAction("refund the payment", "org-1")).thenReturn(Optional.of(candidate));
        when(promoter.promote(candidate, tenantContext)).thenReturn(SemanticActionPromotionResult.builder()
                .successful(false)
                .validationErrors(List.of("action is not registered"))
                .build());

        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), tenantContext);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getValidationErrors()).contains("action is not registered");
    }

    @Test
    void returnsCanonicalActionWhenPromotionSucceeds() {
        SemanticExtractionCandidate candidate = SemanticExtractionCandidate.builder()
                .candidateActionType("PAYMENT:REFUND")
                .candidateTargetResource("PAYMENT_API")
                .build();
        CanonicalAction canonicalAction = CanonicalAction.builder()
                .actionId("action-1")
                .organizationId("org-1")
                .agentId("agent-1")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of())
                .timestamp(Instant.now())
                .build();

        when(llmProviders.getIfAvailable()).thenReturn(provider);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.extractCandidateAction("refund the payment", "org-1")).thenReturn(Optional.of(candidate));
        when(promoter.promote(candidate, tenantContext)).thenReturn(SemanticActionPromotionResult.builder()
                .successful(true)
                .canonicalAction(canonicalAction)
                .validationErrors(List.of())
                .parametersSchemaEnforced(true)
                .build());

        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), tenantContext);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getCanonicalAction()).isSameAs(canonicalAction);
        assertThat(result.isRequiresSemanticFallback()).isTrue();
        verify(provider).extractCandidateAction("refund the payment", "org-1");
        verify(promoter).promote(candidate, tenantContext);
    }

    @Test
    void failsClosedWhenContextIsNotAnAgent() {
        // The promoter is the authority on identity; a non-agent context must never
        // produce a canonical action through the semantic path.
        when(llmProviders.getIfAvailable()).thenReturn(provider);
        when(provider.isAvailable()).thenReturn(true);
        SemanticExtractionCandidate candidate = SemanticExtractionCandidate.builder()
                .candidateActionType("PAYMENT:REFUND")
                .candidateTargetResource("PAYMENT_API")
                .build();
        when(provider.extractCandidateAction(eq("refund the payment"), any())).thenReturn(Optional.of(candidate));
        when(promoter.promote(eq(candidate), any(TenantContext.class)))
                .thenReturn(SemanticActionPromotionResult.builder()
                        .successful(false)
                        .validationErrors(List.of("semantic promotion requires an authenticated agent principal"))
                        .build());

        TenantContext userContext = TenantContext.builder()
                .organizationId("org-1").userId("user-1").principalType("USER").build();
        NormalizationResult result = normalizer.normalize(promptOnly("refund the payment"), userContext);

        assertThat(result.isSuccessful()).isFalse();
    }
}
