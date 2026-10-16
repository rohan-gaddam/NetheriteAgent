package io.netherite.testsupport;

import io.netherite.auth.session.SessionTokenHasher;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WS6 shared fixture for the single designated authorized person.
 *
 * <p>Every WS6 suite needs the same three facts established before it can make
 * an assertion: an organization exists, one member of it is <em>designated</em>,
 * and a browser-shaped session cookie exists for a given user. Reimplementing
 * that per suite is how role-based fixtures drifted apart before, so the
 * designation and session minting now live in exactly one place.</p>
 *
 * <p>The fixture deliberately writes the designation the same way production
 * does — a nullable column on {@code organizations} — rather than through any
 * notion of a role, so a suite cannot accidentally assert against an
 * authorization model the application no longer has.</p>
 *
 * <p>Instances are plain objects, not Spring beans: a test constructs one from
 * the repositories it has already injected, which keeps the fixture usable in
 * both transactional (rolled back) and non-transactional (explicitly cleaned up)
 * suites.</p>
 */
public final class DesignatedPersonFixture {

    /** The session cookie name the application issues on login. */
    public static final String SESSION_COOKIE = "neth_sid";

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;

    private final List<UUID> createdOrganizations = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdSessions = new ArrayList<>();

    public DesignatedPersonFixture(OrganizationRepository organizationRepository,
                                   UserRepository userRepository,
                                   UserSessionRepository userSessionRepository) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.userSessionRepository = userSessionRepository;
    }

    /** Creates an ACTIVE organization that starts with nobody designated. */
    public OrganizationEntity newOrganization(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(label)
                .slug(slug(label) + "-" + suffix)
                .status("ACTIVE")
                .build());
        organizationRepository.flush();
        createdOrganizations.add(org.getId());
        return org;
    }

    /** Creates an ACTIVE, non-designated member of {@code org}. */
    public UserEntity newMember(OrganizationEntity org, String label) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(slug(label) + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Fixture " + label)
                .status("ACTIVE")
                .build());
        userRepository.flush();
        createdUsers.add(user.getId());
        return user;
    }

    /**
     * Makes {@code person} the organization's single designated authorized
     * person, replacing anyone who held it.
     */
    public UserEntity designate(OrganizationEntity org, UserEntity person) {
        OrganizationEntity fresh = organizationRepository.findById(org.getId()).orElseThrow();
        fresh.setAuthorizedPersonUserId(person.getId());
        organizationRepository.save(fresh);
        organizationRepository.flush();
        return person;
    }

    /** Returns the organization to the fail-closed, unadministered state. */
    public void clearDesignation(OrganizationEntity org) {
        OrganizationEntity fresh = organizationRepository.findById(org.getId()).orElseThrow();
        fresh.setAuthorizedPersonUserId(null);
        organizationRepository.save(fresh);
        organizationRepository.flush();
    }

    /** Reads back the current designation, or {@code null} when nobody holds it. */
    public UUID currentDesignation(OrganizationEntity org) {
        return organizationRepository.findById(org.getId())
                .map(OrganizationEntity::getAuthorizedPersonUserId)
                .orElse(null);
    }

    /**
     * Mints a live session for {@code user} without going through login.
     *
     * <p>The row is flushed immediately so that a filter reading it on another
     * code path sees it, which is what a real login would have produced.</p>
     */
    public Cookie sessionFor(UserEntity user) {
        return sessionFor(user, "fixture");
    }

    /** Same as {@link #sessionFor(UserEntity)} with a debuggable token prefix. */
    public Cookie sessionFor(UserEntity user, String prefix) {
        String raw = prefix + "-" + UUID.randomUUID();
        UserSessionEntity session = userSessionRepository.save(UserSessionEntity.builder()
                .userId(user.getId())
                .organizationId(user.getOrganizationId())
                .sessionToken(SessionTokenHasher.hash(raw))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());
        userSessionRepository.flush();
        createdSessions.add(session.getId());
        return new Cookie(SESSION_COOKIE, raw);
    }

    /**
     * The organizations this fixture created, in creation order.
     *
     * <p>A non-transactional suite needs the ids to clean up rows the
     * transactional suites get back from their rollback.</p>
     */
    public List<UUID> createdOrganizationIds() {
        return List.copyOf(createdOrganizations);
    }

    /**
     * Deletes everything this fixture created.
     *
     * <p>Required only by suites that are not {@code @Transactional}; a
     * transactional suite is cleaned up by its rollback and can ignore this.</p>
     */
    public void deleteCreated() {
        createdSessions.forEach(id -> userSessionRepository.findById(id).ifPresent(userSessionRepository::delete));
        createdUsers.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        createdOrganizations.forEach(id -> organizationRepository.findById(id).ifPresent(organizationRepository::delete));
        createdSessions.clear();
        createdUsers.clear();
        createdOrganizations.clear();
        organizationRepository.flush();
    }

    private static String slug(String label) {
        return label.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
