package io.netherite.api;

import io.netherite.api.model.control.AddDomainRequest;
import io.netherite.api.model.control.AuthorizedPersonView;
import io.netherite.api.model.control.BootstrapAuthorizedPersonRequest;
import io.netherite.api.model.control.CreateOrganizationRequest;
import io.netherite.api.model.control.DomainView;
import io.netherite.api.model.control.IdpConfigRequest;
import io.netherite.api.model.control.IdpConfigView;
import io.netherite.api.model.control.OrganizationView;
import io.netherite.api.model.control.TransferAuthorizedPersonRequest;
import io.netherite.api.model.control.VerifyDomainRequest;
import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.OrganizationDomainEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationDomainRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.service.OrganizationAuthorizationService;
import io.netherite.domain.service.OrganizationOnboardingService;
import io.netherite.domain.service.OrganizationOwnershipService;
import io.netherite.security.config.AuthProperties;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Control-plane organization onboarding API.
 *
 * SECURITY:
 *  - Every /v1/control/** endpoint requires an authenticated human session.
 *    Agent API-key principals are rejected. (WS6: the previous ORG_ADMIN role
 *    requirement is removed — authorization is by designated person, not role.)
 *  - Creating organizations and managing any organization OTHER than the
 *    caller's own requires the configured owner (the explicit bootstrap exception).
 *  - Managing one's own organization requires being that organization's single
 *    designated authorized person.
 *  - Organization IDs are never accepted from unauthenticated/untrusted callers —
 *    the tenant comes from the trusted session context established by
 *    UserAuthenticationFilter. There is no client-controlled organization selection.
 *  - The IdP client secret is write-only and never echoed back.
 */
@Slf4j
@RestController
@RequestMapping("/v1/control")
@RequiredArgsConstructor
public class ControlPlaneController {

    private final OrganizationOnboardingService onboardingService;
    private final OrganizationRepository organizationRepository;
    private final OrganizationDomainRepository organizationDomainRepository;
    private final OrganizationIdpConfigRepository idpConfigRepository;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final OrganizationOwnershipService organizationOwnershipService;

    // ===== Owner-scoped operations: create + global listing =====

    @PostMapping("/organizations")
    public ResponseEntity<?> createOrganization(@RequestBody CreateOrganizationRequest request) {
        if (!isOwner()) {
            return forbidden();
        }
        try {
            OrganizationEntity org = onboardingService.createOrganization(request.name(), request.slug());
            return ResponseEntity.status(HttpStatus.CREATED).body(toOrganizationView(org));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    @GetMapping("/organizations")
    public ResponseEntity<?> listOrganizations() {
        if (!isOwner()) {
            return forbidden();
        }
        List<OrganizationView> views = organizationRepository.findAll().stream()
                .map(this::toOrganizationView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(views);
    }

    // ===== WS6: authorized person lifecycle =====

    /**
     * Reads an organization's current authorized person.
     *
     * <p>Readable by the organization's own designated person and by the
     * deployment owner. Returns a null {@code userId} for an organization that
     * has never had a person designated.</p>
     */
    @GetMapping("/organizations/{orgId}/authorized-person")
    public ResponseEntity<?> getAuthorizedPerson(@PathVariable("orgId") UUID orgId) {
        if (!isOwner() && !isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        if (organizationRepository.findById(orgId).isEmpty()) {
            return notFound("Organization not found");
        }
        return ResponseEntity.ok(toAuthorizedPersonView(organizationAuthorizationService.findAuthorizedPerson(orgId).orElse(null)));
    }

    /**
     * Designates an organization's FIRST authorized person.
     *
     * <p>Restricted to the deployment owner. This is the trusted bootstrap that
     * unblocks organizations left fail-closed by the nullable column: existing
     * organizations have nobody designated after V12, and they cannot self-claim
     * (there would be no authorized person to authorize the claim). It refuses to
     * overwrite an existing designation, so it can never seize an organization
     * that already has an owner.</p>
     */
    @PostMapping("/organizations/{orgId}/authorized-person")
    public ResponseEntity<?> bootstrapAuthorizedPerson(@PathVariable("orgId") UUID orgId,
                                                       @RequestBody BootstrapAuthorizedPersonRequest request) {
        if (!isOwner()) {
            return forbidden();
        }
        try {
            UUID userId = parseUuid(request == null ? null : request.userId(), "userId");
            OrganizationEntity org = organizationOwnershipService.bootstrapAuthorizedPerson(
                    orgId, userId, callerId(), requestId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(toAuthorizedPersonView(organizationAuthorizationService.findAuthorizedPerson(orgId).orElse(null)));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    /**
     * Transfers an organization's authorized person.
     *
     * <p>Only the organization's CURRENT designated person may initiate this;
     * there is no role, hierarchy or second administrator who could also do so.
     * The new person must already be an active member of the same organization.
     * {@code expectedCurrentUserId} makes the transfer safe against concurrent
     * attempts — see {@link OrganizationOwnershipService#transferAuthorizedPerson}.</p>
     */
    @PutMapping("/organizations/{orgId}/authorized-person")
    public ResponseEntity<?> transferAuthorizedPerson(@PathVariable("orgId") UUID orgId,
                                                      @RequestBody TransferAuthorizedPersonRequest request) {
        if (!isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        try {
            UUID userId = parseUuid(request == null ? null : request.userId(), "userId");
            UUID expected = parseUuid(request == null ? null : request.expectedCurrentUserId(), "expectedCurrentUserId");
            organizationOwnershipService.transferAuthorizedPerson(
                    orgId, userId, callerId(), expected, requestId());
            return ResponseEntity.ok(toAuthorizedPersonView(organizationAuthorizationService.findAuthorizedPerson(orgId).orElse(null)));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    /**
     * Clears the designation, returning the organization to the fail-closed
     * state where nobody can administer its configuration.
     *
     * <p>This is the deliberate lockout escape hatch. It is owner-only rather
     * than available to the designated person, so an organization cannot be
     * accidentally stranded with no possible administrator.</p>
     */
    @DeleteMapping("/organizations/{orgId}/authorized-person")
    public ResponseEntity<?> clearAuthorizedPerson(@PathVariable("orgId") UUID orgId) {
        if (!isOwner()) {
            return forbidden();
        }
        try {
            organizationOwnershipService.clearAuthorizedPersonAsDeploymentOwner(orgId, callerId(), requestId());
            return ResponseEntity.ok(toAuthorizedPersonView(null));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    // ===== Organization-scoped operations =====

    @PutMapping("/organizations/{orgId}/domains")
    public ResponseEntity<?> addDomain(@PathVariable("orgId") UUID orgId, @RequestBody AddDomainRequest request) {
        if (!isOwner() && !isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        try {
            OrganizationDomainEntity domain = onboardingService.addDomain(orgId, request.domain(), false);
            return ResponseEntity.ok(toDomainView(domain));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    @PostMapping("/organizations/{orgId}/domains/{domainId}/verify")
    public ResponseEntity<?> setDomainVerified(@PathVariable("orgId") UUID orgId,
                                               @PathVariable("domainId") UUID domainId,
                                               @RequestBody VerifyDomainRequest request) {
        if (!isOwner() && !isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        try {
            OrganizationDomainEntity domain = onboardingService.setDomainVerified(orgId, domainId, request.verified());
            return ResponseEntity.ok(toDomainView(domain));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    @DeleteMapping("/organizations/{orgId}/domains/{domainId}")
    public ResponseEntity<?> removeDomain(@PathVariable("orgId") UUID orgId, @PathVariable("domainId") UUID domainId) {
        if (!isOwner() && !isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        onboardingService.removeDomain(orgId, domainId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/organizations/{orgId}/idp")
    public ResponseEntity<?> upsertIdpConfig(@PathVariable("orgId") UUID orgId, @RequestBody IdpConfigRequest request) {
        if (!isOwner() && !isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        try {
            OrganizationIdpConfigEntity config = onboardingService.upsertIdpConfig(orgId, toInput(request));
            return ResponseEntity.ok(toIdpConfigView(config));
        } catch (NetheriteException e) {
            return error(e);
        }
    }

    @DeleteMapping("/organizations/{orgId}/idp")
    public ResponseEntity<?> removeIdpConfig(@PathVariable("orgId") UUID orgId) {
        if (!isOwner() && !isDesignatedPersonOf(orgId)) {
            return forbidden();
        }
        onboardingService.removeIdpConfig(orgId);
        return ResponseEntity.noContent().build();
    }

    // ===== Authorization helpers =====

    /**
     * Resolves the human caller of a control-plane operation.
     *
     * <p>WS6: there is no role check. A control-plane caller is any ACTIVE human
     * session. What they may then DO is decided by {@link #isOwner()} and
     * {@link #isDesignatedPersonOf(UUID)} — the deployment owner (a configuration
     * fact, not a role) and the organization's single designated authorized
     * person respectively. Agent API-key principals never qualify.</p>
     */
    private UserEntity resolveControlPlaneCaller() {
        TenantContext ctx = TenantContextHolder.getContext();
        if (ctx == null || !"USER".equals(ctx.getPrincipalType())) {
            return null;
        }
        if (ctx.getUserId() == null) {
            return null;
        }
        UserEntity user = userRepository.findById(UUID.fromString(ctx.getUserId())).orElse(null);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return null;
        }
        return user;
    }

    private boolean isOwner() {
        UserEntity caller = resolveControlPlaneCaller();
        if (caller == null) {
            return false;
        }
        String ownerEmail = authProperties.getOwner().getEmail();
        return ownerEmail != null && !ownerEmail.isBlank()
                && ownerEmail.trim().equalsIgnoreCase(caller.getEmail());
    }

    /**
     * WS6: the caller is the authorized person of {@code orgId}.
     *
     * <p>Replaces the old {@code isAdminOf}, which granted access to any
     * ORG_ADMIN of the organization. The check is delegated to the single trusted
     * decision point and compares against the organization's designated person,
     * so it cannot drift from the filter.</p>
     */
    private boolean isDesignatedPersonOf(UUID orgId) {
        UserEntity caller = resolveControlPlaneCaller();
        if (caller == null) {
            return false;
        }
        // Guard the cross-tenant case explicitly: the caller must be asking
        // about their own organization, and must be that organization's person.
        if (!caller.getOrganizationId().equals(orgId)) {
            return false;
        }
        return organizationAuthorizationService.isAuthorizedPerson(orgId, caller.getId());
    }

    private static OrganizationOnboardingService.IdpConfigInput toInput(IdpConfigRequest r) {
        return new OrganizationOnboardingService.IdpConfigInput(
                r.issuerUrl(), r.clientId(), r.clientSecret(), r.discoveryUrl(),
                r.authorizationEndpoint(), r.tokenEndpoint(), r.userinfoEndpoint(),
                r.jwksUri(), r.scopes(), r.claimEmail(), r.claimName(), r.claimRoles(),
                r.claimGroups(), r.adminClaimName(), r.adminClaimValue(), r.redirectUri(), r.status());
    }

    // ===== View mapping =====

    private OrganizationView toOrganizationView(OrganizationEntity org) {
        List<DomainView> domains = organizationDomainRepository.findByOrganizationId(org.getId()).stream()
                .map(this::toDomainView)
                .collect(Collectors.toList());
        IdpConfigView idp = idpConfigRepository.findByOrganizationId(org.getId())
                .map(this::toIdpConfigView)
                .orElse(null);
        return new OrganizationView(
                org.getId().toString(),
                org.getName(),
                org.getSlug(),
                org.getStatus(),
                formatInstant(org.getCreatedAt()),
                domains,
                idp);
    }

    private DomainView toDomainView(OrganizationDomainEntity domain) {
        return new DomainView(
                domain.getId().toString(),
                domain.getDomain(),
                domain.isVerified(),
                formatInstant(domain.getCreatedAt()));
    }

    private IdpConfigView toIdpConfigView(OrganizationIdpConfigEntity config) {
        return new IdpConfigView(
                config.getId().toString(),
                config.getOrganizationId().toString(),
                config.getIssuerUrl(),
                config.getClientId(),
                config.getClientSecret() != null && !config.getClientSecret().isBlank(),
                config.getDiscoveryUrl(),
                config.getAuthorizationEndpoint(),
                config.getTokenEndpoint(),
                config.getUserinfoEndpoint(),
                config.getJwksUri(),
                config.getScopes(),
                config.getClaimEmail(),
                config.getClaimName(),
                config.getClaimRoles(),
                config.getClaimGroups(),
                config.getAdminClaimName(),
                config.getAdminClaimValue(),
                config.getRedirectUri(),
                config.getStatus(),
                formatInstant(config.getUpdatedAt()));
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC).toString();
    }

    private AuthorizedPersonView toAuthorizedPersonView(UserEntity person) {
        if (person == null) {
            return null;
        }
        return new AuthorizedPersonView(
                person.getOrganizationId().toString(),
                person.getId().toString(),
                person.getEmail(),
                person.getFullName(),
                "ACTIVE".equals(person.getStatus()),
                person.getCreatedAt());
    }

    /** The authenticated caller's id, or null when there is no human caller. */
    private UUID callerId() {
        UserEntity caller = resolveControlPlaneCaller();
        return caller != null ? caller.getId() : null;
    }

    /**
     * Correlation id for the ownership audit trail. Derived server-side from the
     * action rather than trusted from the client, so audit entries cannot be
     * forged or collide across callers.
     */
    private static String requestId() {
        return "cp-authorized-person-" + UUID.randomUUID();
    }

    private static UUID parseUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    field + " is required");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    field + " is not a valid identifier");
        }
    }

    private static ResponseEntity<?> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "ERR_RESOURCE_NOT_FOUND",
                "message", message,
                "timestamp", Instant.now().toString()));
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "FORBIDDEN",
                "message", "You are not authorized to perform this operation",
                "timestamp", Instant.now().toString()));
    }

    private static ResponseEntity<?> error(NetheriteException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(Map.of(
                "error", e.getErrorCode().name(),
                "message", e.getDetails()));
    }
}
