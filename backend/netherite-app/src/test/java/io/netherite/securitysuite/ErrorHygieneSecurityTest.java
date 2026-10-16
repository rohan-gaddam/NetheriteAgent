package io.netherite.securitysuite;

import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch H â€” dedicated security suite.
 *
 * <p>Error-response hygiene: authentication and authorization failures must answer with
 * the canonical error shape and must never echo credentials, secrets, or JVM stack
 * frames, on any management or action surface.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ErrorHygieneSecurityTest {

    private static final String[] LEAK_PATTERNS = {
            "ntk_live_", "$2a$", "$2b$", "java.", "at io.netherite",
            "Exception", "Caused by", "StackOverflow"
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentDomainService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult agent;

    /** Shared designated-person fixture: the only place WS6 suites mint people. */
    private DesignatedPersonFixture fixture;

    /** The organization's designated authorized person, used by HYG-3/HYG-4. */
    private Cookie designatedCookie;

    @BeforeEach
    void setUp() {
        fixture = new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
        organization = organizationService.createOrganization(
                "Hygiene Corp", "hyg-" + UUID.randomUUID().toString().substring(0, 8));
        agent = agentDomainService.registerAgent(
                organization.getId(), "HygieneAgent", "Error hygiene agent", "TIER_1");
        UserEntity person = fixture.newMember(organization, "hygiene-admin");
        fixture.designate(organization, person);
        // WS6: designate the caller so it is authorized to reach the surfaces
        // under test; HYG-3/HYG-4 are about error hygiene, not the denial itself.
        designatedCookie = fixture.sessionFor(person, "hyg-admin");
    }

    @Test
    @DisplayName("HYG-1. Unknown key rejection does not echo the key, a secret, or a stack trace")
    void unknownKeyRejectionIsHygienic() throws Exception {
        mockMvc.perform(get("/v1/agents")
                        .header("X-Netherite-Agent-Key", "ntk_live_unknown999.canary-value-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"))
                .andExpect(content().string(not(containsString("canary-value-123"))))
                .andExpect(content().string(not(containsString("ntk_live_"))))
                .andExpect(content().string(not(containsString("java."))));
    }

    @Test
    @DisplayName("HYG-2. Revoked key rejection does not expose internals")
    void revokedKeyRejectionIsHygienic() throws Exception {
        AgentDomainService.GeneratedApiKeyResult generated = agentDomainService.createApiKey(
                organization.getId(), agent.agent().getId(), "temp", null);
        agentDomainService.revokeApiKey(organization.getId(), generated.keyId());

        mockMvc.perform(get("/v1/agents")
                        .header("X-Netherite-Agent-Key", generated.fullApiKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("Caused by"))));
    }

    @Test
    @DisplayName("HYG-3. Authorization-denial bodies carry the canonical error shape and no leakage")
    void forbiddenBodyIsHygienic() throws Exception {
        mockMvc.perform(get("/v1/zzz").cookie(designatedCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    @DisplayName("HYG-4. Approve rejection carries no stack trace")
    void invalidTicketIdRejectionIsHygienic() throws Exception {
        // WS6: the designated person legitimately reaches this endpoint (approval
        // authority belongs to that single person), so a malformed ticket id is now
        // rejected by the handler itself. The controller deliberately reports a
        // not-found rather than a parse error, so a caller cannot distinguish a
        // malformed id from an absent ticket.
        mockMvc.perform(post("/v1/approvals/{id}/approve", "not-a-uuid")
                        .cookie(designatedCookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("Caused by"))))
                .andExpect(content().string(not(containsString("UUID"))));
    }

    @Test
    @DisplayName("HYG-5. Every tested error path stays free of known leak patterns")
    void allErrorPathsStayClean() throws Exception {
        String response = mockMvc.perform(get("/v1/agents").header("X-Netherite-Agent-Key",
                        "ntk_live_invalid.invalidsecret"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain(LEAK_PATTERNS);
    }
}