package io.netherite.api.model.control;

/**
 * IdP configuration view. The client secret is never echoed back — only its
 * presence is reported so operators can confirm it was stored.
 */
public record IdpConfigView(
        String id,
        String organizationId,
        String issuerUrl,
        String clientId,
        boolean clientSecretConfigured,
        String discoveryUrl,
        String authorizationEndpoint,
        String tokenEndpoint,
        String userinfoEndpoint,
        String jwksUri,
        String scopes,
        String claimEmail,
        String claimName,
        String claimRoles,
        String claimGroups,
        String adminClaimName,
        String adminClaimValue,
        String redirectUri,
        String status,
        String updatedAt
) {
}