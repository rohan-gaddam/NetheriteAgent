package io.netherite.connectors.spi;

import java.net.URI;

/**
 * SSRF & DNS Egress Guard to prevent dispatching calls to private RFC1918 networks,
 * loopback addresses, or cloud instance metadata endpoints.
 */
public interface SsrfGuardValidator {

    /**
     * Validates whether the given URI is permitted for outbound connection.
     * Throws an exception or returns false if target resolves to a forbidden/private IP.
     */
    boolean isUriPermitted(URI targetUri);

    /**
     * Validates a destination that is configured by the control-plane operator (OIDC endpoints,
     * OPA, notification webhooks). Implementations may permit private-network destinations for
     * these trusted, operator-configured endpoints whereas {@link #isUriPermitted} stays strict.
     * Defaults to strict behavior for safety.
     */
    default boolean isUriPermittedForOperatorConfigured(URI targetUri) {
        return isUriPermitted(targetUri);
    }
}
