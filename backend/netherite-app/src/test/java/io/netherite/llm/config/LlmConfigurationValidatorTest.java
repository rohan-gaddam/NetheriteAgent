package io.netherite.llm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigurationValidatorTest {

    @Test
    void acceptsNoneProvider() {
        assertThat(LlmConfigurationValidator.validateProvider("none")).isEmpty();
        assertThat(LlmConfigurationValidator.validateProvider(null)).isEmpty();
        assertThat(LlmConfigurationValidator.validateProvider("  ")).isEmpty();
    }

    @Test
    void acceptsGeminiProviderCaseInsensitively() {
        assertThat(LlmConfigurationValidator.validateProvider("gemini")).isEmpty();
        assertThat(LlmConfigurationValidator.validateProvider("GEMINI")).isEmpty();
    }

    @Test
    void acceptsOpenRouterProviderCaseInsensitively() {
        assertThat(LlmConfigurationValidator.validateProvider("openrouter")).isEmpty();
        assertThat(LlmConfigurationValidator.validateProvider("OPENROUTER")).isEmpty();
    }

    @Test
    void rejectsUnknownProvider() {
        assertThat(LlmConfigurationValidator.validateProvider("openai"))
                .hasSize(1)
                .allMatch(e -> e.contains("netherite.llm.provider"));
    }
}
