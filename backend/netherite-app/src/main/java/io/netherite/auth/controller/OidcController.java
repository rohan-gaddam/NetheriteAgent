package io.netherite.auth.controller;

import io.netherite.auth.session.SessionCookieService;
import io.netherite.auth.service.OrganizationDiscoveryService;
import io.netherite.auth.service.OrganizationDiscoveryService.DiscoveryResult;
import io.netherite.auth.service.OidcService;
import io.netherite.domain.entity.OidcFlowEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OidcFlowRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.observability.IdentityHash;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/auth/oidc")
@RequiredArgsConstructor
public class OidcController {

    private final OidcService oidcService;
    private final OrganizationDiscoveryService discoveryService;
    private final OrganizationIdpConfigRepository idpConfigRepository;
    private final OrganizationRepository organizationRepository;
    private final OidcFlowRepository oidcFlowRepository;
    private final UserSessionRepository userSessionRepository;
    private final SessionCookieService sessionCookieService;
    private final NetheriteMetrics metrics;

    /** Frontend base URL for post-authentication redirects. */
    @Value("${netherite.auth.oidc.callback-base-url:http://localhost:3000}")
    private String callbackBaseUrl;

    /** Backend external base URL as seen by the IdP (used for redirect_uri). */
    @Value("${netherite.auth.oidc.external-base-url:http://localhost:8080}")
    private String externalBaseUrl;

    @Value("${netherite.auth.oidc.flow-timeout-seconds:300}")
    private int flowTimeoutSeconds;

    /** Master switch for the SSO path. When disabled, SSO fails closed — it is never silently emulated. */
    @Value("${netherite.auth.oidc.enabled:false}")
    private boolean oidcEnabled;

    /**
     * Organization discovery endpoint.
     * POST /v1/auth/oidc/discover  { "email": "alice@acme.com" }
     *
     * Returns a safe discovery result — no secrets, no internal IDs.
     * Domain discovery is routing only; it does NOT grant access.
     */
    @PostMapping("/discover")
    public ResponseEntity<?> discover(@RequestBody Map<String, String> body) {
        if (!oidcEnabled) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "errorCode", "sso_disabled",
                    "message", "Single sign-on is disabled. Contact your administrator."));
        }
        String email = body.get("email");

        DiscoveryResult result = discoveryService.discoverFromEmail(email);

        return switch (result.status()) {
            case SUCCESS -> ResponseEntity.ok(Map.of(
                    "available", true));
            case INVALID_EMAIL -> ResponseEntity.badRequest().body(Map.of(
                    "available", false,
                    "errorCode", "invalid_email",
                    "message", "Please enter a valid work email address."));
            case UNKNOWN_ORGANIZATION -> ResponseEntity.ok(Map.of(
                    "available", false,
                    "errorCode", "unknown_organization",
                    "message", "We couldn't find an organization associated with that work email. "
                            + "Check your email address or contact your administrator."));
            case SSO_NOT_CONFIGURED -> ResponseEntity.ok(Map.of(
                    "available", false,
                    "errorCode", "sso_not_configured",
                    "message", "Single sign-on isn't configured for this organization yet. "
                            + "Contact your organization's administrator."));
        };
    }

    /**
     * Start OIDC flow — discover organization from email, resolve IdP,
     * generate state/nonce/PKCE, redirect to the organization's IdP.
     *
     * GET /v1/auth/oidc/start?email=alice@acme.com
     * Legacy: GET /v1/auth/oidc/start?idp={issuerUrl}  (direct IdP lookup)
     */
    @GetMapping("/start")
    public void startOidcFlow(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "idp", required = false) String issuerUrl,
            @RequestParam(value = "org", required = false) String orgSlug,
            HttpServletResponse response) throws Exception {

        OrganizationIdpConfigEntity idpConfig;

        try {
            if (!oidcEnabled) {
                redirectWithError(response, "sso_disabled");
                return;
            }
            if (email != null && !email.isBlank()) {
                // Primary flow: email-based organization discovery
                DiscoveryResult result = discoveryService.discoverFromEmail(email);
                idpConfig = switch (result.status()) {
                    case SUCCESS -> result.idpConfig();
                    case INVALID_EMAIL -> {
                        redirectWithError(response, "invalid_email");
                        yield null;
                    }
                    case UNKNOWN_ORGANIZATION -> {
                        redirectWithError(response, "unknown_organization");
                        yield null;
                    }
                    case SSO_NOT_CONFIGURED -> {
                        redirectWithError(response, "sso_not_configured");
                        yield null;
                    }
                };
                if (idpConfig == null) return;
            } else if (issuerUrl != null && !issuerUrl.isBlank()) {
                // Legacy: direct IdP issuer lookup
                idpConfig = idpConfigRepository.findByIssuerUrlAndStatus(issuerUrl.trim(), "ACTIVE")
                        .orElse(null);
                if (idpConfig == null) {
                    redirectWithError(response, "sso_unavailable");
                    return;
                }
            } else if (orgSlug != null && !orgSlug.isBlank()) {
                // Legacy: org slug lookup — resolve org then its IdP config
                var orgOpt = organizationRepository.findBySlug(orgSlug.trim());
                if (orgOpt.isEmpty()) {
                    redirectWithError(response, "unknown_organization");
                    return;
                }
                idpConfig = idpConfigRepository
                        .findByOrganizationIdAndStatus(orgOpt.get().getId(), "ACTIVE")
                        .orElse(null);
                if (idpConfig == null) {
                    redirectWithError(response, "sso_not_configured");
                    return;
                }
            } else {
                // No email, no idp, no org — cannot discover
                redirectWithError(response, "missing_email");
                return;
            }

            // If authorization endpoint is missing, attempt OIDC discovery from issuer
            if (idpConfig.getAuthorizationEndpoint() == null || idpConfig.getAuthorizationEndpoint().isBlank()) {
                try {
                    var discovery = oidcService.discover(idpConfig.getIssuerUrl());
                    idpConfig.setAuthorizationEndpoint(discovery.authorizationEndpoint());
                    if (idpConfig.getTokenEndpoint() == null) {
                        idpConfig.setTokenEndpoint(discovery.tokenEndpoint());
                    }
                    if (idpConfig.getJwksUri() == null) {
                        idpConfig.setJwksUri(discovery.jwksUri());
                    }
                    idpConfigRepository.save(idpConfig);
                } catch (OidcService.OidcException e) {
                    log.error("OIDC start: discovery failed for issuer {}", idpConfig.getIssuerUrl(), e);
                    redirectWithError(response, "sso_not_configured");
                    return;
                }
            }

            // Generate state, nonce, PKCE code_verifier and code_challenge
            SecureRandom secureRandom = new SecureRandom();
            String state = randomToken(secureRandom);
            String nonce = randomToken(secureRandom);
            String codeVerifier = randomToken(secureRandom);

            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] challengeHash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeHash);

            // redirect_uri must point to THIS backend's callback endpoint,
            // registered with the IdP. Prefer the IdP config's redirect_uri if set.
            String redirectUri = (idpConfig.getRedirectUri() != null && !idpConfig.getRedirectUri().isBlank())
                    ? idpConfig.getRedirectUri()
                    : externalBaseUrl + "/v1/auth/oidc/callback";

            // Persist flow state
            OidcFlowEntity flow = OidcFlowEntity.builder()
                    .state(state)
                    .nonce(nonce)
                    .codeVerifier(codeVerifier)
                    .organizationId(idpConfig.getOrganizationId())
                    .idpConfigId(idpConfig.getId())
                    .redirectUri(redirectUri)
                    .expiresAt(Instant.now().plus(flowTimeoutSeconds, ChronoUnit.SECONDS))
                    .build();
            oidcFlowRepository.save(flow);

            // Build authorization URL
            String authUrl = idpConfig.getAuthorizationEndpoint()
                    + (idpConfig.getAuthorizationEndpoint().contains("?") ? "&" : "?")
                    + "response_type=code"
                    + "&client_id=" + encode(idpConfig.getClientId())
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&scope=" + encode(idpConfig.getScopes() != null ? idpConfig.getScopes() : "openid email profile")
                    + "&state=" + encode(state)
                    + "&nonce=" + encode(nonce)
                    + "&code_challenge=" + encode(codeChallenge)
                    + "&code_challenge_method=S256";

            // The anti-CSRF state is deliberately never logged (WS8 privacy), and the
            // organization identifier is hashed.
            log.info("OIDC flow started: issuer={}, org={}",
                    idpConfig.getIssuerUrl(),
                    IdentityHash.shortHash(idpConfig.getOrganizationId().toString()));

            response.sendRedirect(authUrl);

        } catch (Exception e) {
            // Unexpected runtime failure — log full trace, redirect with distinct code
            log.error("OIDC start: unexpected internal error", e);
            redirectWithError(response, "internal_error");
        }
    }

    /**
     * OIDC callback — validate code, exchange for tokens, validate identity,
     * enforce Control Plane Admin authorization, create session.
     *
     * GET /v1/auth/oidc/callback?code=...&state=...
     */
    @GetMapping("/callback")
    public ResponseEntity<?> handleCallback(
            @RequestParam(value = "code", required = false) String authorizationCode,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletRequest request) {

        // Fail closed: when SSO is administratively disabled, never process callbacks.
        if (!oidcEnabled) {
            return redirectToLogin("sso_disabled");
        }

        // IdP-reported error. The code is bounded; the IdP-supplied error_description is
        // free text and is deliberately not logged (WS8 safe logging).
        if (error != null) {
            metrics.authFailure("oidc");
            SecurityEvents.warn(log, "security.auth_failure", "OIDC callback error from IdP: code={}", error);
            return redirectToLogin("oidc_failed");
        }

        if (authorizationCode == null || state == null) {
            return redirectToLogin("oidc_failed");
        }

        // Validate state
        OidcFlowEntity flow = oidcFlowRepository.findByState(state).orElse(null);
        if (flow == null) {
            metrics.authFailure("oidc");
            SecurityEvents.warn(log, "security.auth_failure", "OIDC callback: invalid state");
            return redirectToLogin("invalid_state");
        }

        if (flow.isExpired()) {
            oidcFlowRepository.delete(flow);
            metrics.authFailure("oidc");
            SecurityEvents.warn(log, "security.auth_failure", "OIDC callback: expired flow state");
            return redirectToLogin("flow_expired");
        }

        // Load IdP config
        OrganizationIdpConfigEntity idpConfig = idpConfigRepository
                .findById(flow.getIdpConfigId())
                .orElse(null);
        if (idpConfig == null || !"ACTIVE".equals(idpConfig.getStatus())) {
            oidcFlowRepository.delete(flow);
            metrics.authFailure("oidc");
            SecurityEvents.warn(log, "security.auth_failure", "OIDC callback: IdP config not found or inactive");
            return redirectToLogin("idp_unavailable");
        }

        try {
            // Exchange authorization code for tokens
            String tokenResponse = oidcService.exchangeCodeForTokens(
                    authorizationCode, idpConfig, flow.getRedirectUri(), flow.getCodeVerifier());

            @SuppressWarnings("unchecked")
            Map<String, Object> tokenData = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(tokenResponse, Map.class);

            String idToken = (String) tokenData.get("id_token");
            if (idToken == null) {
                throw new OidcService.OidcException("No id_token in token response");
            }

            // Full ID token validation: issuer, audience, exp, nbf, nonce, signature
            var signedJwt = oidcService.validateIdToken(
                    idToken, idpConfig.getIssuerUrl(), idpConfig.getClientId(),
                    flow.getNonce(), idpConfig);

            // Find/create user + enforce Control Plane Admin authorization
            UserEntity user = oidcService.findOrCreateUser(signedJwt, idpConfig);

            // Create server-side session (same architecture as owner login). Only the SHA-256
            // digest is stored at rest; the raw credential travels in the HttpOnly cookie.
            String rawSessionToken = UUID.randomUUID().toString();
            UserSessionEntity session = UserSessionEntity.builder()
                    .userId(user.getId())
                    .organizationId(user.getOrganizationId())
                    .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawSessionToken))
                    .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                    .build();
            session = userSessionRepository.save(session);

            oidcFlowRepository.delete(flow);

            log.info("OIDC authentication successful: userId={}, org={}",
                IdentityHash.shortHash(user.getId().toString()),
                IdentityHash.shortHash(user.getOrganizationId().toString()));

            // Deliver the session credential exclusively via an HttpOnly cookie.
            // It is never placed in the redirect URL or any other client-visible surface.
            String frontendUrl = callbackBaseUrl + "/auth/callback";
            return ResponseEntity.status(HttpServletResponse.SC_FOUND)
                    .header(HttpHeaders.LOCATION, frontendUrl)
                    .header(HttpHeaders.SET_COOKIE,
                            sessionCookieService.createSessionCookie(rawSessionToken, request).toString())
                    .build();

        } catch (OidcService.OidcException e) {
            oidcFlowRepository.delete(flow);
            metrics.authFailure("oidc");
            if (e.isUnauthorized()) {
                // Authenticated at IdP but NOT a Control Plane Admin
                SecurityEvents.warn(log, "security.authorization_denied",
                        "OIDC callback: user authenticated but not authorized");
                return redirectToLogin("unauthorized");
            }
            SecurityEvents.warn(log, "security.auth_failure", "OIDC authentication failed");
            return redirectToLogin("authentication_failed");
        } catch (Exception e) {
            oidcFlowRepository.delete(flow);
            log.error("OIDC callback processing failed", e);
            return redirectToLogin("internal_error");
        }
    }

    private void redirectWithError(HttpServletResponse response, String errorCode) throws java.io.IOException {
        response.sendRedirect(callbackBaseUrl + "/login?error=" + errorCode);
    }

    private ResponseEntity<?> redirectToLogin(String error) {
        return ResponseEntity.status(HttpServletResponse.SC_FOUND)
                .header("Location", callbackBaseUrl + "/login?error=" + error)
                .build();
    }

    private static String randomToken(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
