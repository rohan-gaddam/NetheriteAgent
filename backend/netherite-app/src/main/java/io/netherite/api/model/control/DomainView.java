package io.netherite.api.model.control;

public record DomainView(
        String id,
        String domain,
        boolean verified,
        String createdAt
) {
}