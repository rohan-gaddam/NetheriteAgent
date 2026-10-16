package io.netherite.domain.service;

import io.netherite.audit.model.AuditRecord;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.DecisionType;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * WS6 — lifecycle of an organization's single designated authorized person.
 *
 * <p>Three operations, all server-trusted:</p>
 * <ul>
 *   <li><b>Bootstrap</b> ({@link #bootstrapAuthorizedPerson}) — the very first
 *       designation, permitted only to the caller who just created the
 *       organization, or the deployment owner. It can never claim an
 *       organization that already has an authorized person.</li>
 *   <li><b>Transfer</b> ({@link #transferAuthorizedPerson}) — replacing the
 *       current person. Only the current person (or the deployment owner acting
 *       on their own organization) may initiate it.</li>
 *   <li><b>Clear</b> ({@link #clearAuthorizedPersonAsDeploymentOwner}) — the
 *       deployment owner returning an organization to the deliberately
 *       unadministered, fail-closed state.</li>
 * </ul>
 *
 * <p>All three serialize on the organization row with a pessimistic write lock so
 * that concurrent requests cannot interleave a read-modify-write. Because the
 * single column is the representation, a race can only ever resolve to one
 * surviving value, and the losing request is rejected.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationOwnershipService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final DataRedactionService dataRedactionService;

    /**
     * Designates the first authorized person for an organization that has none.
     *
     * <p>This is the only path that can establish ownership from outside, and it
     * fails closed: an organization that already has a designated person cannot
     * be claimed this way. Callers must independently establish that the actor
     * created the organization or is the deployment owner.</p>
     *
     * @param organizationId organization being bootstrapped
     * @param userId         the user to designate
     * @param actorUserId    the authenticated user performing the designation
     * @param requestId      correlation id for the audit trail
     */
    @Transactional
    public OrganizationEntity bootstrapAuthorizedPerson(UUID organizationId, UUID userId,
                                                        UUID actorUserId, String requestId) {
        OrganizationEntity org = lockOrganization(organizationId);
        if (org.getAuthorizedPersonUserId() != null) {
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "This organization already has a designated authorized person");
        }
        UserEntity candidate = requireActiveMember(userId, organizationId);
        org.setAuthorizedPersonUserId(candidate.getId());
        OrganizationEntity saved = organizationRepository.save(org);
        recordOwnershipEvent(saved, candidate, actorUserId, "ORGANIZATION_AUTHORIZED_PERSON_BOOTSTRAPPED", requestId);
        return saved;
    }

    /**
     * Replaces the organization's authorized person with {@code newUserId}.
     *
     * <p><b>Concurrency.</b> The caller must prove they are the CURRENT
     * authorized person <em>and</em> that {@code expectedCurrentUserId} is still
     * the current one. Both are re-read under the organization row lock, so two
     * simultaneous transfer attempts cannot both succeed: the first to take the
     * lock changes the column, and the second finds its expectation stale and is
     * rejected. Checking authority only before taking the lock would let two
     * requests that were both valid a moment earlier each commit a different
     * winner.</p>
     *
     * @param organizationId        organization being transferred
     * @param newUserId             the incoming authorized person
     * @param actorUserId           the authenticated user performing the transfer
     * @param expectedCurrentUserId the designation the actor believes is current
     */
    @Transactional
    public OrganizationEntity transferAuthorizedPerson(UUID organizationId, UUID newUserId,
                                                        UUID actorUserId, UUID expectedCurrentUserId,
                                                        String requestId) {
        OrganizationEntity org = lockOrganization(organizationId);
        UUID current = org.getAuthorizedPersonUserId();
        if (current == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "This organization has no authorized person to transfer");
        }
        if (!current.equals(expectedCurrentUserId)) {
            throw new NetheriteException(SystemErrorCode.ERR_CONFLICT,
                    "The designated authorized person changed while this request was in progress");
        }
        if (!current.equals(actorUserId)) {
            // Re-verified under the lock: the actor must still be the person.
            throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                    "Only the organization's designated authorized person may transfer that designation");
        }
        if (current.equals(newUserId)) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "That person is already the designated authorized person");
        }
        UserEntity previous = userRepository.findByIdAndOrganizationId(current, organizationId).orElse(null);

        UserEntity candidate = requireActiveMember(newUserId, organizationId);
        org.setAuthorizedPersonUserId(candidate.getId());
        OrganizationEntity saved = organizationRepository.save(org);

        recordOwnershipEvent(saved, candidate, actorUserId, "ORGANIZATION_AUTHORIZED_PERSON_TRANSFERRED", requestId,
                Map.of("previousAuthorizedPersonUserId",
                        previous != null ? previous.getId().toString() : "UNRESOLVED"));
        return saved;
    }

    /**
     * Clears the designation, returning the organization to the fail-closed
     * state where nobody can administer its configuration.
     *
     * <p>This is the deliberate lockout escape hatch, and it is the deployment
     * owner's alone. The organization's designated person cannot clear their own
     * designation: that would let a single request permanently strand the
     * organization with nobody able to administer it, and no path back. The
     * owner exists precisely so that the fail-closed state is always
     * recoverable.</p>
     *
     * <p>As with {@link #bootstrapAuthorizedPerson}, callers must independently
     * establish deployment-owner status; this method does not consult the owner
     * configuration itself. The current holder is still re-read under the row
     * lock so the audit record names the person who actually held the
     * designation at the moment it was removed.</p>
     */
    @Transactional
    public OrganizationEntity clearAuthorizedPersonAsDeploymentOwner(UUID organizationId,
                                                                      UUID actorUserId, String requestId) {
        OrganizationEntity org = lockOrganization(organizationId);
        UUID current = org.getAuthorizedPersonUserId();
        if (current == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "This organization has no authorized person to clear");
        }
        UserEntity previous = userRepository.findByIdAndOrganizationId(current, organizationId).orElse(null);
        org.setAuthorizedPersonUserId(null);
        OrganizationEntity saved = organizationRepository.save(org);

        recordOwnershipEvent(saved, previous, actorUserId, "ORGANIZATION_AUTHORIZED_PERSON_CLEARED", requestId,
                Map.of("previousAuthorizedPersonUserId",
                        previous != null ? previous.getId().toString() : "UNRESOLVED"));
        return saved;
    }

    /**
     * Designates the deployment owner as an organization's authorized person, but
     * only if the organization has none.
     *
     * <p>Used by the owner login path, which must be able to establish the very
     * first designation without a pre-existing session to authorize it. It
     * deliberately differs from {@link #bootstrapAuthorizedPerson} in that it
     * takes no user-supplied target and never fails when the organization is
     * already designated: an existing designation always wins, so repeated owner
     * logins can never displace the person already in charge.</p>
     *
     * <p>Designation still happens under the organization row lock and is still
     * audited, so the owner login is not a back door around the invariants the
     * other two operations uphold.</p>
     *
     * @return the organization, re-read and updated
     */
    @Transactional
    public OrganizationEntity bootstrapOwnerIfUndesignated(UUID organizationId, UUID ownerUserId,
                                                           String requestId) {
        OrganizationEntity org = lockOrganization(organizationId);
        if (org.getAuthorizedPersonUserId() != null) {
            // Someone is already in charge: never take that away.
            return org;
        }
        org.setAuthorizedPersonUserId(ownerUserId);
        OrganizationEntity saved = organizationRepository.save(org);
        recordOwnershipEvent(saved, userRepository.findById(ownerUserId).orElse(null), ownerUserId,
                "ORGANIZATION_OWNER_BOOTSTRAPPED", requestId);
        return saved;
    }

    private OrganizationEntity lockOrganization(UUID organizationId) {
        return organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "Organization not found: " + organizationId));
    }

    /**
     * The new authorized person must be an active member of this organization.
     * Cross-organization designation is impossible here and is independently
     * prevented by the composite foreign key.
     */
    private UserEntity requireActiveMember(UUID userId, UUID organizationId) {
        if (userId == null) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "An authorized person must be specified");
        }
        UserEntity user = userRepository.findByIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_FOUND,
                        "The specified person is not a member of this organization"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "The specified person is not an active member of this organization");
        }
        return user;
    }

    /**
     * Records the ownership change in the existing append-only audit chain.
     *
     * <p>Only identifiers and the organization slug are recorded — never email
     * addresses, tokens or credential material. Written in the same transaction
     * as the change so a rolled-back transfer leaves no orphaned audit entry
     * claiming a change that did not happen.</p>
     */
    private void recordOwnershipEvent(OrganizationEntity org, UserEntity subject, UUID actorUserId,
                                      String eventType, String requestId) {
        recordOwnershipEvent(org, subject, actorUserId, eventType, requestId, Map.of());
    }

    private void recordOwnershipEvent(OrganizationEntity org, UserEntity subject, UUID actorUserId,
                                      String eventType, String requestId, Map<String, Object> extra) {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("authorizedPersonUserId", subject != null ? subject.getId().toString() : null);
        parameters.put("organizationSlug", org.getSlug());
        parameters.putAll(extra);

        auditService.record(AuditRecord.builder()
                .organizationId(org.getId().toString())
                .requestId(requestId != null ? requestId : eventType)
                .eventType(eventType)
                .userId(actorUserId != null ? actorUserId.toString() : null)
                .actionType(eventType)
                .targetResource("organization:" + org.getId())
                .decision(DecisionType.ALLOW)
                .redactedParameters(dataRedactionService.redactParameters(org.getId().toString(), parameters))
                .build());
    }
}
