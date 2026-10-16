package io.netherite.llm.config;

import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.ingestion.promotion.SemanticActionPromoter;
import io.netherite.ingestion.spi.ActionParameterSchemaProvider;
import io.netherite.ingestion.spi.RegisteredActionCatalog;
import io.netherite.llm.provider.GeminiLlmProvider;
import io.netherite.llm.provider.OpenRouterLlmProvider;
import io.netherite.llm.spi.LlmProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the provider-independent semantic-normalization subsystem.
 *
 * <p>The single deterministic {@link SemanticActionPromoter} is always available (it is
 * the security gate for any candidate). The {@link LlmProvider} is opt-in: exactly one
 * provider bean exists when {@code netherite.llm.provider} names it (gemini or
 * openrouter — the selector admits a single value, so no fallback chain can form),
 * otherwise no provider bean exists and the semantic path is disabled (fail-closed).</p>
 */
@Configuration
public class LlmProviderConfiguration {

    @Bean
    public SemanticActionPromoter semanticActionPromoter(
            RegisteredActionCatalog registeredActionCatalog,
            List<ActionParameterSchemaProvider> schemaProviders) {
        return new SemanticActionPromoter(registeredActionCatalog, schemaProviders);
    }

    @Bean
    @ConditionalOnProperty(name = "netherite.llm.provider", havingValue = LlmProperties.PROVIDER_GEMINI)
    public LlmProvider geminiLlmProvider(LlmProperties properties,
                                         ObjectProvider<SsrfGuardValidator> ssrfGuardProvider) {
        return new GeminiLlmProvider(properties, ssrfGuardProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "netherite.llm.provider", havingValue = LlmProperties.PROVIDER_OPENROUTER)
    public LlmProvider openRouterLlmProvider(LlmProperties properties,
                                             ObjectProvider<SsrfGuardValidator> ssrfGuardProvider) {
        return new OpenRouterLlmProvider(properties, ssrfGuardProvider);
    }
}
