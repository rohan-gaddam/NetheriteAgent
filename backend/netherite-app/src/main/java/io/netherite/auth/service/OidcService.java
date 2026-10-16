package io.netherite.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OidcService {

    private final OrganizationIdpConfigRepository idpConfigRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final SsrfGuardValidator ssrfGuardValidator;

    /**
     * Discover OIDC endpoints from the issuer's well-known configuration.
     */
    public OidcDiscovery discover(String issuerUrl) throws OidcException {
        String wellKnownUrl = issuerUrl.endsWith("/")
                ? issuerUrl + ".well-known/openid-configuration"
                : issuerUrl + "/.well-known/openid-configuration";

        try {
            String bodyJson = oidcHttpCall(wellKnownUrl, "OIDC discovery failed", null);

            @SuppressWarnings("unchecked")
            Map<String, Object> config = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(bodyJson, Map.class);

            return OidcDiscovery.builder()
                    .issuer((String) config.get("issuer"))
                    .authorizationEndpoint((String) config.get("authorization_endpoint"))
                    .tokenEndpoint((String) config.get("token_endpoint"))
                    .userinfoEndpoint((String) config.get("userinfo_endpoint"))
                    .jwksUri((String) config.get("jwks_uri"))
                    .build();
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcException("OIDC discovery failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validate an ID token JWT against the IdP's JWKS.
     * Validates: issuer, audience, expiration, not-before, nonce, RS256 signature.
     * Fails closed on any validation failure.
     */
    public SignedJWT validateIdToken(String idTokenString, String expectedIssuer,
                                      String expectedAudience, String expectedNonce,
                                      OrganizationIdpConfigEntity idpConfig) throws OidcException {
        try {
            SignedJWT jwt = SignedJWT.parse(idTokenString);

            String issuer = jwt.getJWTClaimsSet().getIssuer();
            if (!expectedIssuer.equals(issuer)) {
                throw new OidcException("Invalid issuer: expected " + expectedIssuer + ", got " + issuer);
            }

            var audience = jwt.getJWTClaimsSet().getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                throw new OidcException("Invalid audience: expected " + expectedAudience);
            }

            // OIDC Core 3.1.3.7: with multiple audiences the azp (authorized
            // party) claim must be present and match our client ID, otherwise a
            // token minted for another audience-listed party could replay here.
            if (audience.size() > 1) {
                String azp = (String) jwt.getJWTClaimsSet().getClaim("azp");
                if (!expectedAudience.equals(azp)) {
                    throw new OidcException(
                            "Invalid azp: multiple audiences require azp matching this client");
                }
            }

            Date expiry = jwt.getJWTClaimsSet().getExpirationTime();
            if (expiry == null || expiry.before(new Date())) {
                throw new OidcException("Token has expired");
            }

            Date notBefore = jwt.getJWTClaimsSet().getNotBeforeTime();
            if (notBefore != null && notBefore.after(Date.from(Instant.now().plusSeconds(30)))) {
                throw new OidcException("Token not yet valid");
            }

            if (expectedNonce != null) {
                String tokenNonce = (String) jwt.getJWTClaimsSet().getClaim("nonce");
                if (!expectedNonce.equals(tokenNonce)) {
                    throw new OidcException("Invalid nonce");
                }
            }

            String jwksUri = idpConfig.getJwksUri();
            if (jwksUri == null) {
                throw new OidcException("JWKS URI not configured");
            }

            String jwksJson = oidcHttpCall(jwksUri, "Failed to fetch JWKS", null);

            JWKSet jwkSet = JWKSet.parse(jwksJson);
            JWSVerifier verifier = null;

            for (JWK jwk : jwkSet.getKeys()) {
                if (jwk instanceof RSAKey rsaKey) {
                    RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
                    if (jwt.getHeader().getKeyID() == null || jwt.getHeader().getKeyID().equals(rsaKey.getKeyID())) {
                        try {
                            verifier = new RSASSAVerifier(publicKey);
                            if (jwt.verify(verifier)) {
                                return jwt;
                            }
                        } catch (Exception e) {
                            // Try next key
                        }
                    }
                }
            }

            throw new OidcException("Invalid token signature: no matching JWKS key");
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcException("Token validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract a claim value by path, supporting dot-notation for nested claims.
     * Examples: "email", "roles", "realm_access.roles", "resource_access.my-app.roles"
     */
    @SuppressWarnings("unchecked")
    public static Object extractClaimByPath(com.nimbusds.jwt.JWTClaimsSet claims, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = claims.toJSONObject();
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Check whether a claim value contains the required admin value.
     * Handles: String claim, List claim, nested path claims.
     * Fails closed — if admin claim is not configured, access is denied.
     */
    public boolean isControlPlaneAdmin(SignedJWT jwt, OrganizationIdpConfigEntity idpConfig) throws OidcException {
        String adminClaimName = idpConfig.getAdminClaimName();
        String adminClaimValue = idpConfig.getAdminClaimValue();

        // Fail closed: admin claim mapping MUST be configured for SSO
        if (adminClaimName == null || adminClaimName.isBlank()) {
            throw OidcException.unauthorized(
                    "Control Plane Admin claim is not configured for this organization. "
                    + "SSO authorization cannot be verified.");
        }

        Object claimValue;
        try {
            claimValue = extractClaimByPath(jwt.getJWTClaimsSet(), adminClaimName);
        } catch (ParseException e) {
            throw new OidcException("Failed to parse token claims: " + e.getMessage(), e);
        }

        if (claimValue == null) {
            return false;
        }

        // If no specific value required, presence of the claim is sufficient
        if (adminClaimValue == null || adminClaimValue.isBlank()) {
            if (claimValue instanceof Collection<?> col) {
                return !col.isEmpty();
            }
            if (claimValue instanceof String str) {
                return !str.isBlank();
            }
            return true;
        }

        // Specific value required
        if (claimValue instanceof String strVal) {
            return adminClaimValue.equals(strVal);
        }
        if (claimValue instanceof Collection<?> col) {
            return col.stream()
                    .map(Object::toString)
                    .anyMatch(adminClaimValue::equals);
        }
        return adminClaimValue.equals(claimValue.toString());
    }

    /**
     * Find or create a user from OIDC claims, enforcing Control Plane Admin authorization.
     *
     * SECURITY: Email domain matching does NOT grant admin access.
     * The user must authenticate at the IdP AND satisfy the configured
     * Control Plane Admin claim requirement.
     */
    public UserEntity findOrCreateUser(SignedJWT jwt, OrganizationIdpConfigEntity idpConfig) throws OidcException {
        try {
            String subject = jwt.getJWTClaimsSet().getSubject();
            String issuer = jwt.getJWTClaimsSet().getIssuer();

            if (subject == null || subject.isBlank()) {
                throw new OidcException("OIDC token missing 'sub' claim");
            }

            // Enforce a verified email address — fail closed. The IdP must assert
            // email_verified=true; a missing, false, or non-boolean value rejects
            // the login even when subject, audience, and admin claim are otherwise
            // valid. Applies to new, linked, and returning users alike: linking or
            // creating an account on an unverified address would let anyone who
            // guesses an address claim it.
            Object emailVerified = jwt.getJWTClaimsSet().getClaim("email_verified");
            if (!Boolean.TRUE.equals(emailVerified)) {
                throw OidcException.unauthorized(
                        "Email address is not verified by the identity provider");
            }

            // Extract email using configured claim name (supports nested paths)
            String emailClaimName = idpConfig.getClaimEmail() != null ? idpConfig.getClaimEmail() : "email";
            Object emailVal = extractClaimByPath(jwt.getJWTClaimsSet(), emailClaimName);
            String email = emailVal instanceof String ? (String) emailVal : null;

            String nameClaimName = idpConfig.getClaimName() != null ? idpConfig.getClaimName() : "name";
            Object nameVal = extractClaimByPath(jwt.getJWTClaimsSet(), nameClaimName);
            String fullName = nameVal instanceof String ? (String) nameVal : null;

            // Enforce Control Plane Admin authorization — FAIL CLOSED
            if (!isControlPlaneAdmin(jwt, idpConfig)) {
                throw OidcException.unauthorized(
                        "Authenticated but not authorized: user does not have the required Control Plane Admin claim");
            }

            // Find existing user by stable OIDC identity (issuer + subject)
            Optional<UserEntity> existingUser = userRepository
                    .findByOidcSubjectAndOidcIssuer(subject, issuer);
            if (existingUser.isPresent()) {
                UserEntity user = existingUser.get();
                if (!"ACTIVE".equals(user.getStatus())) {
                    throw OidcException.unauthorized("User account is not active");
                }
                // Enforce user belongs to the expected organization (tenant isolation)
                if (!idpConfig.getOrganizationId().equals(user.getOrganizationId())) {
                    throw OidcException.unauthorized(
                            "Cross-tenant identity rejected: user belongs to a different organization");
                }
                return user;
            }

            // Find existing user by email for first SSO login (link identity)
            if (email != null && !email.isBlank()) {
                Optional<UserEntity> emailUser = userRepository.findByEmail(email);
                if (emailUser.isPresent()) {
                    UserEntity user = emailUser.get();
                    // Tenant isolation: email must belong to the same organization
                    if (!idpConfig.getOrganizationId().equals(user.getOrganizationId())) {
                        throw OidcException.unauthorized(
                                "Cross-tenant identity rejected: email belongs to a different organization");
                    }
                    user.setOidcSubject(subject);
                    user.setOidcIssuer(issuer);
                    user.setIdpProvider(idpConfig.getClientId());
                    if (fullName != null && !fullName.isBlank()) {
                        user.setFullName(fullName);
                    }
                    // WS6: no role is assigned. A newly authenticated user is a
                    // plain member of the organization; it becomes its authorized
                    // person only through an explicit designation.
                    return userRepository.save(user);
                }
            }

            // Create new user in the organization
            OrganizationEntity org = organizationRepository
                    .findById(idpConfig.getOrganizationId())
                    .orElseThrow(() -> OidcException.internal("Organization not found"));

            UserEntity newUser = UserEntity.builder()
                    .organizationId(org.getId())
                    .email(email != null ? email : subject + "@" + issuer)
                    .fullName(fullName)
                    .oidcSubject(subject)
                    .oidcIssuer(issuer)
                    .idpProvider(idpConfig.getClientId())
                    .status("ACTIVE")
                    .build();

            return userRepository.save(newUser);
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcException("Failed to process OIDC token: " + e.getMessage(), e);
        }
    }

    /**
     * Exchange the authorization code for tokens at the IdP's token endpoint.
     *
     * SECURITY: The IdP token response body is never included in exception
     * messages — it may contain id_token/access_token credentials.
     */
    public String exchangeCodeForTokens(String code, OrganizationIdpConfigEntity idpConfig,
                                        String redirectUri, String codeVerifier) throws OidcException {
        String tokenEndpoint = idpConfig.getTokenEndpoint();
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            throw new OidcException("Token endpoint not configured");
        }

        String body = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(idpConfig.getClientId(), StandardCharsets.UTF_8)
                + "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8);

        if (idpConfig.getClientSecret() != null && !idpConfig.getClientSecret().isBlank()) {
            body += "&client_secret=" + URLEncoder.encode(idpConfig.getClientSecret(), StandardCharsets.UTF_8);
        }

        try {
            return oidcHttpCall(tokenEndpoint, "Token exchange failed", body);
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcException("Token exchange failed: " + e.getMessage(), e);
        }
    }

    /**
     * Perform an outbound OIDC HTTP call (GET or form-encoded POST) against an
     * operator-configured IdP endpoint, protected by the SSRF guard. Redirects are
     * never followed and connect/request timeouts are enforced.
     */
    private String oidcHttpCall(String url, String failureLabel, String formBody) throws OidcException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new OidcException(failureLabel + ": invalid URI");
        }

        if (!ssrfGuardValidator.isUriPermittedForOperatorConfigured(uri)) {
            log.warn("OIDC endpoint rejected by SSRF guard: {}", url);
            throw new OidcException(failureLabel + ": endpoint failed SSRF validation");
        }

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(10));
            if (formBody != null) {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody));
            } else {
                builder.GET();
            }

            java.net.http.HttpResponse<String> response = client.send(builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new OidcException(failureLabel + ": HTTP " + response.statusCode());
            }

            return response.body();
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcException(failureLabel + ": " + e.getMessage(), e);
        }
    }

    public record OidcDiscovery(
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            String userinfoEndpoint,
            String jwksUri
    ) {
        public static OidcDiscoveryBuilder builder() {
            return new OidcDiscoveryBuilder();
        }
    }

    public static class OidcDiscoveryBuilder {
        private String issuer;
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String userinfoEndpoint;
        private String jwksUri;

        public OidcDiscoveryBuilder issuer(String issuer) { this.issuer = issuer; return this; }
        public OidcDiscoveryBuilder authorizationEndpoint(String ae) { this.authorizationEndpoint = ae; return this; }
        public OidcDiscoveryBuilder tokenEndpoint(String te) { this.tokenEndpoint = te; return this; }
        public OidcDiscoveryBuilder userinfoEndpoint(String ue) { this.userinfoEndpoint = ue; return this; }
        public OidcDiscoveryBuilder jwksUri(String j) { this.jwksUri = j; return this; }
        public OidcDiscovery build() { return new OidcDiscovery(issuer, authorizationEndpoint, tokenEndpoint, userinfoEndpoint, jwksUri); }
    }

    public static class OidcException extends Exception {
        private final int httpStatus;

        public OidcException(String message) {
            super(message);
            this.httpStatus = 401;
        }

        public OidcException(String message, Throwable cause) {
            super(message, cause);
            this.httpStatus = 401;
        }

        private OidcException(String message, int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public boolean isUnauthorized() {
            return httpStatus == 403;
        }

        public static OidcException unauthorized(String message) {
            return new OidcException(message, 403);
        }

        public static OidcException internal(String message) {
            return new OidcException(message, 500);
        }
    }
}
