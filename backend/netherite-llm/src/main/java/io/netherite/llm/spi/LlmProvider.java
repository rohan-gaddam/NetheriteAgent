package io.netherite.llm.spi;

import io.netherite.llm.model.SemanticExtractionCandidate;

import java.util.Optional;

/**
 * Pluggable SPI for semantic understanding and natural-language-to-action translation.
 * NEVER makes authorization or routing decisions.
 */
public interface LlmProvider {

    /**
     * Extracts an untrusted candidate action from natural language input.
     */
    Optional<SemanticExtractionCandidate> extractCandidateAction(String rawPrompt, String organizationId);

    /**
     * Checks if the LLM provider is available.
     */
    boolean isAvailable();
}
