package io.netherite.security.principal;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable trusted security principal established after cryptographically authenticating an AI agent.
 */
@Builder
public record AuthenticatedAgentPrincipal(
        UUID organizationId,
        UUID agentId,
        String keyIdentifier,
        String trustTier,
        String authenticationMechanism, // API_KEY, WORKLOAD_JWT, MTLS
        Instant authenticatedAt
) {
    public AuthenticatedAgentPrincipal {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId cannot be null in AuthenticatedAgentPrincipal");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("agentId cannot be null in AuthenticatedAgentPrincipal");
        }
        if (keyIdentifier == null) {
            throw new IllegalArgumentException("keyIdentifier cannot be null in AuthenticatedAgentPrincipal");
        }
    }
}
