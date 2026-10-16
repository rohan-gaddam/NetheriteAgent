package io.netherite.api.model.control;

import java.util.List;

/**
 * Control plane organization view. Never includes credentials.
 * The IdP config carries only a boolean indicating whether a secret is configured.
 */
public record OrganizationView(
        String id,
        String name,
        String slug,
        String status,
        String createdAt,
        List<DomainView> domains,
        IdpConfigView idp
) {
}