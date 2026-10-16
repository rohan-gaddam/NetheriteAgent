package io.netherite.llm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fail-fast startup validation for the LLM provider selector.
 *
 * <p>A typo in {@code netherite.llm.provider} must not silently disable semantic
 * normalization — it fails startup with a clear message. Missing credentials for an
 * enabled provider fail earlier, when the provider bean is constructed. The test profile
 * is exempt.</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class LlmConfigurationValidator implements ApplicationRunner {

    private final LlmProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        List<String> errors = validateProvider(properties.effectiveProvider());
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid LLM configuration detected. Refusing to start. Errors:\n  - "
                            + String.join("\n  - ", errors));
        }
        log.info("LLM provider configuration validated (provider={})", properties.effectiveProvider());
    }

    /**
     * Pure validation logic, independent of Spring, for direct unit testing.
     */
    public static List<String> validateProvider(String provider) {
        List<String> errors = new ArrayList<>();
        String normalized = provider == null || provider.isBlank()
                ? LlmProperties.PROVIDER_NONE
                : provider.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(LlmProperties.PROVIDER_NONE)
                && !normalized.equals(LlmProperties.PROVIDER_GEMINI)
                && !normalized.equals(LlmProperties.PROVIDER_OPENROUTER)) {
            errors.add("netherite.llm.provider must be one of '" + LlmProperties.PROVIDER_NONE
                    + "', '" + LlmProperties.PROVIDER_GEMINI
                    + "' or '" + LlmProperties.PROVIDER_OPENROUTER + "' (got '" + normalized + "').");
        }
        return errors;
    }
}
