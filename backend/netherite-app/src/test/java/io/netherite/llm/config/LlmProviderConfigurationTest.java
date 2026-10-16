package io.netherite.llm.config;

import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.ingestion.promotion.SemanticActionPromoter;
import io.netherite.ingestion.spi.ActionParameterSchemaProvider;
import io.netherite.ingestion.spi.RegisteredActionCatalog;
import io.netherite.llm.provider.GeminiLlmProvider;
import io.netherite.llm.provider.OpenRouterLlmProvider;
import io.netherite.llm.spi.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmProviderConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void noProviderBeanWhenProviderIsNone() {
        runner.withPropertyValues("netherite.llm.provider=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LlmProvider.class);
                    assertThat(context).hasSingleBean(SemanticActionPromoter.class);
                });
    }

    @Test
    void noProviderBeanWhenProviderIsUnset() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LlmProvider.class);
        });
    }

    @Test
    void geminiProviderIsCreatedWhenConfiguredWithKey() {
        runner.withPropertyValues(
                        "netherite.llm.provider=gemini",
                        "netherite.llm.gemini.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmProvider.class);
                    assertThat(context.getBean(LlmProvider.class)).isInstanceOf(GeminiLlmProvider.class);
                });
    }

    @Test
    void contextFailsFastWhenGeminiEnabledWithoutKey() {
        runner.withPropertyValues("netherite.llm.provider=gemini")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("GEMINI_API_KEY");
                });
    }

    @Test
    void openRouterProviderIsCreatedWhenConfiguredWithKey() {
        runner.withPropertyValues(
                        "netherite.llm.provider=openrouter",
                        "netherite.llm.openrouter.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmProvider.class);
                    assertThat(context.getBean(LlmProvider.class)).isInstanceOf(OpenRouterLlmProvider.class);
                });
    }

    @Test
    void contextFailsFastWhenOpenRouterEnabledWithoutKey() {
        runner.withPropertyValues("netherite.llm.provider=openrouter")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("OPENROUTER_API_KEY");
                });
    }

    @Configuration
    @EnableConfigurationProperties(LlmProperties.class)
    @Import(LlmProviderConfiguration.class)
    static class TestConfiguration {

        @Bean
        RegisteredActionCatalog registeredActionCatalog() {
            return mock(RegisteredActionCatalog.class);
        }

        @Bean
        ActionParameterSchemaProvider actionParameterSchemaProvider() {
            return mock(ActionParameterSchemaProvider.class);
        }

        @Bean
        SsrfGuardValidator ssrfGuardValidator() {
            return mock(SsrfGuardValidator.class);
        }
    }
}
