package io.netherite.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Configuration for the LLM semantic-normalization subsystem ({@code netherite.llm}).
 *
 * <p>The subsystem is provider-driven and disabled by default: {@code provider=none}
 * means no {@code LlmProvider} bean is created and every semantic request fails closed.
 * A provider is only ever engaged for semantic interpretation; it never makes an
 * authorization, routing or approval decision.</p>
 *
 * <p>Credentials are never stored here: {@code gemini.api-key} is bound from the
 * {@code GEMINI_API_KEY} environment variable (and {@code openrouter.api-key}
 * from {@code OPENROUTER_API_KEY}) through {@code application.yml} placeholders
 * and is never logged, echoed, or persisted.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "netherite.llm")
public class LlmProperties {

    /** Provider disabled: no semantic interpretation is available. */
    public static final String PROVIDER_NONE = "none";

    /** Google Gemini {@code generateContent} provider. */
    public static final String PROVIDER_GEMINI = "gemini";

    /** OpenRouter OpenAI-compatible {@code chat/completions} provider. */
    public static final String PROVIDER_OPENROUTER = "openrouter";

    /** Selected provider. Defaults to {@link #PROVIDER_NONE}. */
    private String provider = PROVIDER_NONE;

    /** Optional model override; when blank the provider default is used. */
    private String model = "";

    /** Request connect/read timeout in milliseconds. */
    private int timeoutMs = 10_000;

    /** Upper bound on generated output tokens. */
    private int maxOutputTokens = 1024;

    /** Upper bound on the raw response body accepted from the provider (bytes). */
    private int maxResponseBytes = 262_144;

    private Gemini gemini = new Gemini();

    private Openrouter openrouter = new Openrouter();

    /** @return the normalized provider selector (never {@code null}). */
    public String effectiveProvider() {
        return provider == null || provider.isBlank()
                ? PROVIDER_NONE
                : provider.trim().toLowerCase(Locale.ROOT);
    }

    @Data
    public static class Gemini {
        /** API base; overridable for tests/mocks. */
        private String baseUrl = "https://generativelanguage.googleapis.com";

        /** API key bound from {@code GEMINI_API_KEY}; never logged. */
        private String apiKey = "";

        /** Model used when {@link LlmProperties#model} is not set. */
        private String defaultModel = "gemini-2.0-flash";
    }

    @Data
    public static class Openrouter {
        /** API base; overridable for tests/mocks. */
        private String baseUrl = "https://openrouter.ai/api/v1";

        /** API key bound from {@code OPENROUTER_API_KEY}; never logged. */
        private String apiKey = "";

        /** Model used when {@link LlmProperties#model} is not set. */
        private String defaultModel = "nvidia/nemotron-3-ultra-550b-a55b:free";
    }
}
