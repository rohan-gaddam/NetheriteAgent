package io.netherite.api;

import io.netherite.auth.session.SessionCookieService;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.OrganizationOwnershipService;
import io.netherite.observability.IdentityHash;
import io.netherite.observability.NetheriteMetrics;
import io.netherite.observability.SecurityEvents;
import io.netherite.security.config.AuthProperties;
import io.netherite.security.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final OrganizationRepository organizationRepository;
    private final AuthProperties authProperties;
    private final io.netherite.domain.service.OrganizationOwnershipService organizationOwnershipService;
    private final SessionCookieService sessionCookieService;
    private final NetheriteMetrics metrics;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        String email = credentials.get("email");
        String password = credentials.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "ERR_INVALID_PARAMETERS",
                    "message", "Email and password are required"));
        }

        String normalizedEmail = email.trim().toLowerCase();

        // Check if this is the special owner account
        String configuredOwnerEmail = authProperties.getOwner().getEmail();
        if (configuredOwnerEmail != null && !configuredOwnerEmail.isBlank()
                && normalizedEmail.equals(configuredOwnerEmail.trim().toLowerCase())) {
            return handleOwnerLogin(normalizedEmail, password, request, response);
        }

        // General user password login: authenticate against the user record
        // persisted in NetheriteAgent's control-plane database. The tenant
        // (organization) identity comes from the user's own database row --
        // never from client-supplied parameters -- so a caller cannot reach
        // another tenant by manipulating request data.
        return handleUserLogin(normalizedEmail, password, request, response);
    }

    /**
     * Authenticate a non-owner user against their stored password hash and
     * establish a session bound to their own organization.
     *
     * <p>The organization (tenant) is resolved exclusively from
     * {@link UserEntity#getOrganizationId()}. No tenant ID is ever accepted
     * from the request body, headers, or query parameters. WS6: no role is
     * assigned -- organizational authority is the single designated authorized
     * person on the organization row, not a user attribute.
     */
    private ResponseEntity<?> handleUserLogin(String email, String password,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        UserEntity user = userRepository.findByEmail(email).orElse(null);

        // Users without a stored password hash (OIDC-only accounts) and
        // unknown accounts both fail with the identical 401 to prevent
        // account enumeration. No account identifier is logged.
        if (user == null || user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            log.debug("User login rejected: no password credential for email");
            return authenticationFailed();
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.debug("User login failed: invalid password");
            return authenticationFailed();
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "ERR_FORBIDDEN",
                    "message", "User account is not active"));
        }

        // Tenant context is bound to the user's own organization. The client
        // cannot supply or override the organization id -- it comes from the
        // authenticated user's database record only.
        UUID orgId = user.getOrganizationId();

        // Create session (same mechanism as owner and OIDC login). Only the
        // SHA-256 digest of the token is stored at rest; the raw credential
        // is delivered via the HttpOnly cookie.
        String rawSessionToken = UUID.randomUUID().toString();
        UserSessionEntity session = UserSessionEntity.builder()
                .userId(user.getId())
                .organizationId(orgId)
                .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawSessionToken))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        session = userSessionRepository.save(session);

        log.info("User authentication successful: userId={}, org={}",
                IdentityHash.shortHash(user.getId().toString()),
                IdentityHash.shortHash(orgId.toString()));

        // Deliver the session credential exclusively via an HttpOnly cookie.
        // It is never included in the response body or logged.
        response.addHeader(HttpHeaders.SET_COOKIE,
                sessionCookieService.createSessionCookie(rawSessionToken, request).toString());

        return ResponseEntity.ok(Map.of(
                "userId", user.getId().toString(),
                "organizationId", orgId.toString(),
                "expiresAt", session.getExpiresAt().toString()));
    }

    private ResponseEntity<?> handleOwnerLogin(String email, String password,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        String ownerPasswordHash = authProperties.getOwner().getPasswordHash();

        // Fail closed if owner password hash is not configured. The response is
        // deliberately identical to a generic authentication failure so a probe
        // cannot distinguish "account exists but server misconfigured" from a
        // plain bad credential (account-enumeration oracle).
        if (ownerPasswordHash == null || ownerPasswordHash.isBlank()) {
            log.error("Owner password hash not configured — authentication rejected");
            return authenticationFailed();
        }

        // Verify password
        if (!passwordEncoder.matches(password, ownerPasswordHash)) {
            log.debug("Owner login failed: invalid password");
            return authenticationFailed();
        }

        // Find or create the owner organization
        String orgSlug = authProperties.getOwner().getOrganizationSlug();
        String orgName = authProperties.getOwner().getOrganizationName();

        OrganizationEntity org = organizationRepository.findBySlug(orgSlug).orElseGet(() -> {
            log.info("Creating owner organization: {} ({})", orgName, orgSlug);
            return organizationRepository.save(OrganizationEntity.builder()
                    .name(orgName)
                    .slug(orgSlug)
                    .status("ACTIVE")
                    .build());
        });
        // Effectively-final id for capture by the lambda below.
        final UUID ownerOrgId = org.getId();

        // Find or create the owner user.
        //
        // WS6: no role is assigned. The owner's authority comes from two
        // configuration facts, not a user attribute: (a) the deployment owner
        // email match, and (b) being the owner organization's designated
        // authorized person, which is established just below.
        UserEntity owner = userRepository.findByEmail(email).orElseGet(() -> {
            log.info("Creating owner user: emailHashed={}", IdentityHash.shortHash(email));
            return userRepository.save(UserEntity.builder()
                    .organizationId(ownerOrgId)
                    .email(email)
                    .passwordHash(ownerPasswordHash)
                    .fullName("Owner")
                    .status("ACTIVE")
                    .build());
        });

        // Ensure owner has correct organization and credential
        boolean needsUpdate = false;
        if (!org.getId().equals(owner.getOrganizationId())) {
            owner.setOrganizationId(org.getId());
            needsUpdate = true;
        }
        if (owner.getPasswordHash() == null) {
            owner.setPasswordHash(ownerPasswordHash);
            needsUpdate = true;
        }
        if (needsUpdate) {
            owner = userRepository.save(owner);
        }

        // WS6 trusted bootstrap: the deployment owner is designated the owner
        // organization's authorized person, and only if nobody has been
        // designated yet. This never steals an existing designation - if the
        // owner organization already has an authorized person, ownership is left
        // with them and the owner is admitted as a plain member.
        //
        // The designation is applied by the ownership service so it takes the
        // organization row lock and lands in the audit chain, exactly like a
        // designation made through the control plane.
        if (org.getAuthorizedPersonUserId() == null) {
            org = organizationOwnershipService.bootstrapOwnerIfUndesignated(
                    org.getId(), owner.getId(), "owner-login");
            log.info("Bootstrapped authorized person for owner organization {}",
                    IdentityHash.shortHash(org.getId().toString()));
        }

        if (!"ACTIVE".equals(owner.getStatus())) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "ERR_FORBIDDEN",
                    "message", "User account is not active"));
        }

        // Create session (same mechanism as any other auth). Only the SHA-256 digest of the
        // token is stored at rest; the raw credential is delivered via the HttpOnly cookie.
        String rawSessionToken = UUID.randomUUID().toString();
        UserSessionEntity session = UserSessionEntity.builder()
                .userId(owner.getId())
                .organizationId(org.getId())
                .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawSessionToken))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        session = userSessionRepository.save(session);

        log.info("Owner authentication successful: userId={}, org={}",
                IdentityHash.shortHash(owner.getId().toString()),
                IdentityHash.shortHash(org.getId().toString()));

        // Deliver the session credential exclusively via an HttpOnly cookie.
        // It is never included in the response body or logged.
        response.addHeader(HttpHeaders.SET_COOKIE,
                sessionCookieService.createSessionCookie(rawSessionToken, request).toString());

        return ResponseEntity.ok(Map.of(
                "userId", owner.getId().toString(),
                "organizationId", org.getId().toString(),
                "expiresAt", session.getExpiresAt().toString()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        String sessionToken = sessionCookieService.readSessionToken(request);

        if (sessionToken != null && !sessionToken.isBlank()) {
            userSessionRepository.findBySessionToken(io.netherite.auth.session.SessionTokenHasher.hash(sessionToken))
                    .ifPresent(userSessionRepository::delete);
        }

        // Always clear the cookie, even if there was nothing to invalidate.
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieService.clearSessionCookie().toString());

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {

        String sessionToken = sessionCookieService.readSessionToken(request);

        if (sessionToken == null || sessionToken.isBlank()) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "ERR_AUTHENTICATION_FAILED",
                    "message", "Session token is required"));
        }

        UserSessionEntity session = sessionCookieService.validateAndLoad(sessionToken);

        if (session == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "ERR_AUTHENTICATION_FAILED",
                    "message", "Invalid session token"));
        }

        UserEntity user = userRepository.findById(session.getUserId()).orElse(null);

        if (user == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "ERR_RESOURCE_NOT_FOUND",
                    "message", "User not found"));
        }

        // Never expose password hash, OIDC subject, or other credentials.
        // WS6: there is no `role` to report — authority is the organization-level
        // designation, which is per-organization and not a property of the user.
        return ResponseEntity.ok(Map.of(
                "userId", user.getId().toString(),
                "email", user.getEmail(),
                "fullName", user.getFullName() != null ? user.getFullName() : "",
                "organizationId", user.getOrganizationId().toString(),
                "status", user.getStatus()));
    }

    /**
     * Uniform authentication failure used by every login rejection path. The
     * identical body across all failure modes prevents account enumeration.
     * WS8: each rejection also bumps the bounded owner-login auth-failure counter
     * and emits one structured security event; no account identifier is logged.
     */
    private ResponseEntity<?> authenticationFailed() {
        metrics.authFailure("owner_login");
        SecurityEvents.warn(log, "security.auth_failure", "Owner login rejected: invalid credentials");
        return ResponseEntity.status(401).body(Map.of(
                "error", "ERR_AUTHENTICATION_FAILED",
                "message", "Invalid email or password"));
    }
}
