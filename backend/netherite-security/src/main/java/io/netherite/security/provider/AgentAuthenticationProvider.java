package io.netherite.security.provider;

import io.netherite.security.principal.AuthenticatedAgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Extensible SPI for authenticating incoming agent requests.
 * Implementations support API Keys, Signed JWT / Workload Identity, or mTLS.
 */
public interface AgentAuthenticationProvider {

    /**
     * Identifies whether this provider supports the incoming request credentials.
     */
    boolean supports(HttpServletRequest request);

    /**
     * Authenticates the request and returns the trusted principal if valid.
     * Returns Optional.empty() if invalid or credentials absent (without throwing info-leaking exceptions).
     */
    Optional<AuthenticatedAgentPrincipal> authenticate(HttpServletRequest request);

    /**
     * Unique identifier of this authentication mechanism.
     */
    String getMechanismName();
}
