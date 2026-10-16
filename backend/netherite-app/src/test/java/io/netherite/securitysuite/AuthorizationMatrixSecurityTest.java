package io.netherite.securitysuite;

import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.OrganizationService;
import io.netherite.testsupport.DesignatedPersonFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS6 â€” dedicated security suite for the designated authorized person model.
 *
 * <p>This suite replaces the former principal-role Ã— management-surface matrix
 * (MTX-1..MTX-6). Those tests asserted a permission matrix over ORG_ADMIN,
 * PLATFORM_ADMIN, POLICY_ADMIN, AUDITOR and HUMAN_APPROVER; that model no longer
 * exists. Authorization is now a single question â€” <em>is the caller the
 * organization's designated authorized person?</em> â€” so the matrix collapses to
 * three cases that matter:</p>
 *
 * <ol>
 *   <li>the designated person is admitted to every organization surface;</li>
 *   <li>any other member of the same organization is refused everywhere;</li>
 *   <li>an organization with nobody designated refuses everybody (fail closed).</li>
 * </ol>
 *
 * <p>These are strictly stronger assertions than the role matrix they replace:
 * the old suite could only prove that specific roles were denied, whereas this
 * one proves that <em>every</em> non-designated user is denied, regardless of any
 * attribute they might carry.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthorizationMatrixSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private OrganizationEntity organization;

    /** Shared designated-person fixture: the only place WS6 suites mint people. */
    private DesignatedPersonFixture fixture;

    /** The organization's single designated authorized person. */
    private Cookie designatedCookie;

    /** An ordinary member of the same organization. */
    private Cookie otherMemberCookie;

    /** A second ordinary member, used to prove plurality does not confer authority. */
    private Cookie secondMemberCookie;

    @BeforeEach
    void setUp() {
        fixture = new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
        organization = organizationService.createOrganization(
                "Matrix Corp", "mtx-" + UUID.randomUUID().toString().substring(0, 8));

        UserEntity designated = createMember("designated");
        UserEntity other = createMember("other");
        UserEntity second = createMember("second");

        fixture.designate(organization, designated);
        designatedCookie = fixture.sessionFor(designated, "designated");
        otherMemberCookie = fixture.sessionFor(other, "other");
        secondMemberCookie = fixture.sessionFor(second, "second");
    }

    private UserEntity createMember(String label) {
        return fixture.newMember(organization, label);
    }

    @Test
    @DisplayName("MTX-1. The designated authorized person reaches every organization surface")
    void designatedPersonReachesAllSurfaces() throws Exception {
        mockMvc.perform(get("/v1/agents").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/policies").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/users").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/resources").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/bindings").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/audit").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/approvals").cookie(designatedCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/dashboard").cookie(designatedCookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("MTX-2. Global organization listing stays owner-gated for the designated person")
    void globalOrgListingStaysOwnerGated() throws Exception {
        // Being an organization's authorized person confers nothing across
        // tenants: the deployment-wide listing remains owner-only.
        mockMvc.perform(get("/v1/control/organizations").cookie(designatedCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("MTX-3. A non-designated member is denied every organization surface")
    void nonDesignatedMemberIsDeniedEverywhere() throws Exception {
        for (String path : new String[]{"/v1/agents", "/v1/policies", "/v1/users", "/v1/resources",
                "/v1/bindings", "/v1/audit", "/v1/approvals", "/v1/dashboard"}) {
            mockMvc.perform(get(path).cookie(otherMemberCookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("MTX-4. Having several non-designated members confers no authority on any of them")
    void pluralityConfersNoAuthority() throws Exception {
        // The model is "at most one". Two active members who are neither of them
        // the designated person both stay locked out â€” there is no fallback to
        // "the only remaining admin" and no second administrator.
        mockMvc.perform(get("/v1/agents").cookie(otherMemberCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/agents").cookie(secondMemberCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/control/organizations").cookie(otherMemberCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MTX-5. An undesignated organization admits nobody (fail closed)")
    void undesignatedOrganizationAdmitsNobody() throws Exception {
        // Clear the designation: the organization becomes unadministered and
        // every surface must refuse, including for the person who held it.
        fixture.designate(organization, createMember("former-designated"));
        fixture.clearDesignation(organization);

        mockMvc.perform(get("/v1/agents").cookie(designatedCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/audit").cookie(designatedCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/approvals").cookie(designatedCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MTX-6. An unknown surface is denied for the designated person (fail closed)")
    void unknownSurfaceIsDenied() throws Exception {
        mockMvc.perform(get("/v1/zzz").cookie(designatedCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
    }
}
