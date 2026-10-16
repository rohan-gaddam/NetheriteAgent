package io.netherite.domain.service;

import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * WS6 — organization authorization: the single designated authorized person.
 *
 * <p>NetheriteAgent deliberately does NOT use role-based access control. Each
 * organization has at most one currently designated authorized person, recorded
 * in {@code organizations.authorized_person_user_id}, and only that person may
 * administer the organization's configuration.</p>
 *
 * <p>This service is the single trusted decision point. Controllers call it
 * rather than re-implementing the check, so the rule cannot drift between
 * endpoints. The frontend never participates in this decision — a direct API
 * call is authorized identically.</p>
 *
 * <p><b>Fail closed.</b> Every path that cannot establish the caller's identity
 * or the organization's designated person denies the operation. An organization
 * with no designated person admits no configuration administration at all, which
 * is the safe state for an organization that has never completed onboarding.</p>
 *
 * <p><b>Scope.</b> This governs organization <em>administration</em> and the
 * human governance surfaces attached to it — audit, dashboard and approval
 * decisions — for the designated person alone. It deliberately grants nothing
 * to a machine principal: agent API keys remain confined to the action surface,
 * so a protected action is always requested by a machine and decided by a human.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationAuthorizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    /**
     * Resolves the organization from the trusted server-side tenant context.
     *
     * <p>The organization is never taken from a request body, path variable or
     * query parameter; it comes from the authenticated session or API key that
     * the authentication filters established.</p>
     */
    public UUID requireTenantOrganizationId() {
        TenantContext context = TenantContextHolder.getContext();
        if (context == null || context.getOrganizationId() == null || context.getOrganizationId().isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_AUTHENTICATION_REQUIRED,
                    "No authenticated organization context");
        }
        try {
            return UUID.fromString(context.getOrganizationId());
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Malformed organization context");
        }
    }

    /** True when the current principal is the organization's designated authorized person. */
    @Transactional(readOnly = true)
    public boolean isAuthorizedPersonForTenant() {
        TenantContext context = TenantContextHolder.getContext();
        if (context == null || !"USER".equals(context.getPrincipalType())) {
            return false;
        }
        UUID orgId;
        UUID userId;
        try {
            orgId = UUID.fromString(context.getOrganizationId());
            userId = UUID.fromString(context.getUserId());
        } catch (RuntimeException e) {
            return false;
        }
        return isAuthorizedPerson(orgId, userId);
    }

    /**
     * Database-backed check that {@code userId} is the organization currently
     * designates as its authorized person. The organization row is authoritative.
     */
    @Transactional(readOnly = true)
    public boolean isAuthorizedPerson(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }
        return organizationRepository.findById(organizationId)
                .map(OrganizationEntity::getAuthorizedPersonUserId)
                .filter(userId::equals)
                .isPresent();
    }

    /**
     * Enforces organization authorization for the caller's own tenant.
     *
     * @throws NetheriteException {@code ERR_FORBIDDEN} when the caller is not the
     *         organization's currently designated authorized person
     */
    @Transactional(readOnly = true)
    public void requireAuthorizedPersonForTenant() {
        requireAuthorizedPerson(requireTenantOrganizationId());
    }

    /**
     * Enforces organization authorization for an explicit organization.
     *
     * <p>Used by the control plane, which legitimately addresses organizations by
     * path variable. Callers must have already proven the relationship between
     * the session and {@code organizationId}; this method only proves the
     * designation itself.</p>
     *
     * @throws NetheriteException {@code ERR_FORBIDDEN} when the caller is not the
     *         designated authorized person of that organization
     */
    @Transactional(readOnly = true)
    public void requireAuthorizedPerson(UUID organizationId) {
        TenantContext context = TenantContextHolder.getContext();
        if (context == null || !"USER".equals(context.getPrincipalType()) || context.getUserId() == null) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Only the organization's designated authorized person may perform this operation");
        }
        UUID callerId;
        try {
            callerId = UUID.fromString(context.getUserId());
        } catch (IllegalArgumentException e) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Only the organization's designated authorized person may perform this operation");
        }
        if (!isAuthorizedPerson(organizationId, callerId)) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Only the organization's designated authorized person may perform this operation");
        }
    }

    /** The organization's designated authorized person, if one has been designated. */
    @Transactional(readOnly = true)
    public Optional<UserEntity> findAuthorizedPerson(UUID organizationId) {
        UUID designated = organizationRepository.findById(organizationId)
                .map(OrganizationEntity::getAuthorizedPersonUserId)
                .orElse(null);
        if (designated == null) {
            return Optional.empty();
        }
        return userRepository.findByIdAndOrganizationId(designated, organizationId);
    }
}
