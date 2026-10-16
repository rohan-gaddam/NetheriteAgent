package io.netherite.api.model.control;

/**
 * Control-plane IdP configuration request. Mirrors the persisted IdP config.
 * A blank clientSecret preserves the previously stored secret on update.
 */
public record IdpConfigRequest(
        String issuerUrl,
        String clientId,
        String clientSecret,
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
        String status
) {
}