package io.netherite.ingestion.model;

/**
 * A positive catalog match: the canonical, registered name of the target resource.
 *
 * <p>Only server-derived data may populate this record. The resource name is treated
 * as the trusted canonical form and is copied onto the promoted
 * {@code CanonicalAction.targetResource}.</p>
 */
public record RegisteredActionTarget(String resourceName) {

    public RegisteredActionTarget {
        if (resourceName == null || resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName is required");
        }
    }
}
