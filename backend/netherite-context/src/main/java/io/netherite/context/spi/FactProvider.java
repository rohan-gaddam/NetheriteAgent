package io.netherite.context.spi;

import io.netherite.common.model.CanonicalAction;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable SPI for retrieving authoritative facts from enterprise systems or control plane state.
 */
public interface FactProvider {

    /**
     * Unique identifier for this fact provider.
     */
    String getProviderId();

    /**
     * The fact keys this provider is capable of resolving.
     */
    Set<String> getSupportedFactKeys();

    /**
     * Resolves requested facts asynchronously.
     */
    CompletableFuture<Map<String, Object>> resolveFacts(CanonicalAction action, Set<String> requestedKeys);
}
