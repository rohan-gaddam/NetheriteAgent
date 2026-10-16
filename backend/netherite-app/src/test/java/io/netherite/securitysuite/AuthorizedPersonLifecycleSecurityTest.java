package io.netherite.securitysuite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.testsupport.DesignatedPersonFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS6 lifecycle and isolation tests for the single designated authorized person.
 *
 * <p>Covers the invariants that the removal of RBAC makes load-bearing:</p>
 * <ul>
 *   <li>an organization with nobody designated admits no configuration at all;</li>
 *   <li>designation and transfer are confined to the organization's own members;</li>
 *   <li>only the deployment owner can bootstrap or clear, and bootstrap never
 *       overwrites an existing designation;</li>
 *   <li>a transfer cannot be lost to a race, and cannot silently replace a
 *       designation that moved underneath the caller;</li>
 *   <li>the designated person alone reaches the governance surfaces, and an
 *       ordinary member of the same organization reaches none of them.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthorizedPersonLifecycleSecurityTest {

    private static final String COOKIE = "neth_sid";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;

    /** Shared designated-person fixture: the only place WS6 suites mint people. */
    private DesignatedPersonFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
    }

    // ======================= fail closed =======================

    @Test
    @DisplayName("AP-1. An organization with nobody designated is closed to every human surface")
    void undesignatedOrganizationFailsClosed() throws Exception {
        OrganizationEntity org = newOrg("Undesignated");
        UserEntity member = newUser(org, "member");
        Cookie session = sessionFor(member);

        // Configuration surfaces.
        assertForbidden(get("/v1/agents", session));
        assertForbidden(get("/v1/resources", session));
        assertForbidden(get("/v1/policies", session));
        assertForbidden(get("/v1/users", session));
        // Governance surfaces.
        assertForbidden(get("/v1/audit", session));
        assertForbidden(get("/v1/dashboard", session));
        assertForbidden(get("/v1/approvals", session));
    }

    @Test
    @DisplayName("AP-2. An ordinary member is refused every surface the designated person may use")
    void ordinaryMemberIsRefusedWhereDesignatedPersonIsAdmitted() throws Exception {
        OrganizationEntity org = newOrg("Mixed");
        UserEntity person = newUser(org, "person");
        designate(org, person);
        UserEntity plainMember = newUser(org, "plain");

        Cookie asPerson = sessionFor(person);
        Cookie asMember = sessionFor(plainMember);

        for (String path : new String[]{"/v1/agents", "/v1/resources", "/v1/policies",
                "/v1/users", "/v1/audit", "/v1/dashboard"}) {
            assertForbidden(get(path, asMember));
        }
        // Sanity: the designated person is admitted on the same surfaces.
        assertAllowed(get("/v1/agents", asPerson));
    }

    // ======================= lifecycle =======================

    @Test
    @DisplayName("AP-3. The deployment owner can bootstrap an undesignated organization")
    void ownerCanBootstrapUndesignatedOrganization() throws Exception {
        OrganizationEntity org = newOrg("NeedsOwner");
        UserEntity candidate = newUser(org, "candidate");

        Cookie owner = ownerSession();
        mockMvc.perform(post("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", candidate.getId().toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(candidate.getId().toString()));
    }

    @Test
    @DisplayName("AP-4. Bootstrap never overwrites an existing designation")
    void bootstrapRefusesToDisplaceExistingDesignation() throws Exception {
        OrganizationEntity org = newOrg("AlreadyOwned");
        UserEntity first = newUser(org, "first");
        designate(org, first);
        UserEntity second = newUser(org, "second");

        Cookie owner = ownerSession();
        mockMvc.perform(post("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", second.getId().toString()))))
                .andExpect(status().isForbidden());

        assertEquals(first.getId(), currentDesignation(org), "the original person must still be in charge");
    }

    @Test
    @DisplayName("AP-5. A non-owner cannot bootstrap, even for their own organization")
    void nonOwnerCannotBootstrap() throws Exception {
        OrganizationEntity org = newOrg("SelfServe");
        UserEntity candidate = newUser(org, "candidate");
        UserEntity designated = newUser(org, "designated");
        designate(org, designated);

        Cookie asDesignated = sessionFor(designated);
        mockMvc.perform(post("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(asDesignated)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", candidate.getId().toString()))))
                .andExpect(status().isForbidden());

        assertEquals(designated.getId(), currentDesignation(org));
    }

    @Test
    @DisplayName("AP-6. The designated person can transfer, and loses their own authority afterwards")
    void designatedPersonCanTransfer() throws Exception {
        OrganizationEntity org = newOrg("Transferable");
        UserEntity from = newUser(org, "from");
        UserEntity to = newUser(org, "to");
        designate(org, from);

        Cookie asFrom = sessionFor(from);
        mockMvc.perform(put("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(asFrom)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", to.getId().toString(),
                                "expectedCurrentUserId", from.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(to.getId().toString()));

        assertEquals(to.getId(), currentDesignation(org));
        // The outgoing person is now an ordinary member and is refused.
        assertForbidden(get("/v1/agents", asFrom));
        assertAllowed(get("/v1/agents", sessionFor(to)));
    }

    @Test
    @DisplayName("AP-7. A transfer whose expected current holder is stale is rejected")
    void staleTransferIsRejected() throws Exception {
        OrganizationEntity org = newOrg("Racy");
        UserEntity from = newUser(org, "from");
        UserEntity third = newUser(org, "third");
        UserEntity rival = newUser(org, "rival");
        designate(org, from);

        // 'rival' believes 'from' is still in charge, but 'third' has since taken over.
        designate(org, third);

        Cookie asRival = sessionFor(rival);
        mockMvc.perform(put("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(asRival)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", rival.getId().toString(),
                                "expectedCurrentUserId", from.getId().toString()))))
                .andExpect(status().isForbidden());

        assertEquals(third.getId(), currentDesignation(org), "the transfer must not have applied");
    }

    @Test
    @DisplayName("AP-8. An ordinary member cannot transfer, and the designation is untouched")
    void ordinaryMemberCannotTransfer() throws Exception {
        OrganizationEntity org = newOrg("Protected");
        UserEntity person = newUser(org, "person");
        UserEntity interloper = newUser(org, "interloper");
        designate(org, person);

        mockMvc.perform(put("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(sessionFor(interloper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", interloper.getId().toString(),
                                "expectedCurrentUserId", person.getId().toString()))))
                .andExpect(status().isForbidden());

        assertEquals(person.getId(), currentDesignation(org));
    }

    @Test
    @DisplayName("AP-9. A transfer target must be an active member of the same organization")
    void transferTargetMustBeActiveSameOrgMember() throws Exception {
        OrganizationEntity org = newOrg("ClosedMembership");
        UserEntity person = newUser(org, "person");
        designate(org, person);

        // A member of a DIFFERENT organization.
        OrganizationEntity other = newOrg("Elsewhere");
        UserEntity outsider = newUser(other, "outsider");

        mockMvc.perform(put("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(sessionFor(person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", outsider.getId().toString(),
                                "expectedCurrentUserId", person.getId().toString()))))
                .andExpect(status().isNotFound());

        assertEquals(person.getId(), currentDesignation(org), "cross-organization designation must be impossible");

        // An INACTIVE member of the same organization is also refused.
        UserEntity suspended = newUser(org, "suspended");
        userRepository.findById(suspended.getId()).ifPresent(u -> {
            u.setStatus("SUSPENDED");
            userRepository.save(u);
        });

        mockMvc.perform(put("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(sessionFor(person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", suspended.getId().toString(),
                                "expectedCurrentUserId", person.getId().toString()))))
                .andExpect(status().isBadRequest());

        assertEquals(person.getId(), currentDesignation(org));
    }

    @Test
    @DisplayName("AP-10. Cross-tenant transfer is impossible: one org's person cannot be designated in another")
    void crossTenantTransferIsImpossible() throws Exception {
        OrganizationEntity victim = newOrg("Victim");
        OrganizationEntity attacker = newOrg("Attacker");
        UserEntity victimPerson = newUser(victim, "victim-person");
        UserEntity attackerPerson = newUser(attacker, "attacker-person");
        designate(victim, victimPerson);
        designate(attacker, attackerPerson);

        // The attacker tries to seize the victim org.
        mockMvc.perform(put("/v1/control/organizations/{orgId}/authorized-person", victim.getId())
                        .cookie(sessionFor(attackerPerson))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", attackerPerson.getId().toString(),
                                "expectedCurrentUserId", victimPerson.getId().toString()))))
                .andExpect(status().isForbidden());

        assertEquals(victimPerson.getId(), currentDesignation(victim));
    }

    // ======================= clear / recovery =======================

    @Test
    @DisplayName("AP-11. The owner can clear a designation, returning the org to fail-closed")
    void ownerCanClearDesignation() throws Exception {
        OrganizationEntity org = newOrg("Stranded");
        UserEntity person = newUser(org, "person");
        designate(org, person);
        Cookie asPerson = sessionFor(person);

        Cookie owner = ownerSession();
        mockMvc.perform(delete("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(owner))
                .andExpect(status().isOk());

        assertNull(currentDesignation(org));
        // The former person is now locked out, which is the point of the escape hatch.
        assertForbidden(get("/v1/agents", asPerson));
    }

    @Test
    @DisplayName("AP-12. The designated person cannot clear their own designation")
    void designatedPersonCannotSelfClear() throws Exception {
        OrganizationEntity org = newOrg("NoSelfClear");
        UserEntity person = newUser(org, "person");
        designate(org, person);

        mockMvc.perform(delete("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(sessionFor(person)))
                .andExpect(status().isForbidden());

        assertEquals(person.getId(), currentDesignation(org), "self-clear must not strand the organization");
    }

    @Test
    @DisplayName("AP-13. After a clear, only the owner can re-establish a person")
    void clearedOrganizationCanOnlyBeRecoveredByOwner() throws Exception {
        OrganizationEntity org = newOrg("Recovery");
        UserEntity person = newUser(org, "person");
        UserEntity successor = newUser(org, "successor");
        designate(org, person);

        Cookie owner = ownerSession();
        mockMvc.perform(delete("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(owner))
                .andExpect(status().isOk());

        // The former person cannot self-recover.
        mockMvc.perform(post("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(sessionFor(person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", successor.getId().toString()))))
                .andExpect(status().isForbidden());

        // The owner can.
        mockMvc.perform(post("/v1/control/organizations/{orgId}/authorized-person", org.getId())
                        .cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", successor.getId().toString()))))
                .andExpect(status().isCreated());

        assertEquals(successor.getId(), currentDesignation(org));
    }

    // ======================= helpers =======================

    /**
     * Builds a GET carrying the session cookie.
     *
     * <p>{@code MockMvcRequestBuilders.get(String, Object...)} treats its
     * variadic argument as URI template variables, so a cookie passed there is
     * silently dropped and the request would be anonymous. Keeping the cookie
     * application explicit is what makes these assertions genuinely reach the
     * authorization filter as the intended principal instead of as an
     * unauthenticated caller, which would turn a meaningful 403 into a vacuous
     * 401.</p>
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(
            String path, Cookie cookie) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).cookie(cookie);
    }

    private void assertForbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(request).andReturn();
        assertEquals(403, result.getResponse().getStatus(),
                "expected 403 but got " + result.getResponse().getStatus()
                        + " body=" + result.getResponse().getContentAsString());
    }

    private void assertAllowed(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private UUID currentDesignation(OrganizationEntity org) {
        return fixture.currentDesignation(org);
    }

    private void designate(OrganizationEntity org, UserEntity person) {
        fixture.designate(org, person);
    }

    private OrganizationEntity newOrg(String label) {
        return fixture.newOrganization(label);
    }

    private UserEntity newUser(OrganizationEntity org, String label) {
        return fixture.newMember(org, label);
    }

    private Cookie sessionFor(UserEntity user) {
        return fixture.sessionFor(user, "ap");
    }

    /**
     * Logs in as the configured deployment owner through the real login endpoint.
     *
     * <p>Owner authority is a configuration fact rather than an attribute of a
     * user row, so the only faithful way to obtain an owner session is to
     * perform the owner login. That flow also creates the owner organization and
     * bootstraps its designated person, which is exactly the production
     * behaviour these tests need to coexist with.</p>
     */
    private Cookie ownerSession() throws Exception {
        String email = System.getProperty("test.owner.email", "owner@example.com");
        String password = System.getProperty("test.owner.password", "Fakepassword@26");
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertNotNull(cookie, "owner login must issue a session cookie");
        return cookie;
    }
}
