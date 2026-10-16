package io.netherite.securitysuite;

import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch H — dedicated security suite.
 *
 * <p>Filter-chain boundary behavior: the order-0 {@code SecurityHeadersFilter} must
 * run before any authentication short-circuit (Cache-Control: no-store on auth
 * failures), and no path normalization trick (trailing slash, repeated slash,
 * capitalization) may turn a protected /v1 surface into a handler-reachable one.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FilterBoundarySecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentDomainService;

    private String agentKey;

    @BeforeEach
    void setUp() {
        OrganizationEntity organization = organizationService.createOrganization(
                "Boundary Corp", "bnd-" + UUID.randomUUID().toString().substring(0, 8));
        AgentDomainService.AgentRegistrationResult agent = agentDomainService.registerAgent(
                organization.getId(), "BoundaryAgent", "Boundary test agent", "TIER_1");
        agentKey = agent.fullApiKey();
    }

    @Test
    @DisplayName("BND-1. Security headers are present even on authentication failures")
    void securityHeadersPresentOnAuthFailure() throws Exception {
        mockMvc.perform(get("/v1/agents"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    @DisplayName("BND-2. Path variants of the management surface fail closed for anonymous callers")
    void pathVariantsFailClosedAnonymous() throws Exception {
        mockMvc.perform(get("/v1/agents/")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/v1//agents")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/v1/AGENTS")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/V1/agents")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("BND-3. Trailing-slash and gateway path tricks cannot turn an agent key into management access")
    void agentKeyCannotAccessManagementViaPathTricks() throws Exception {
        mockMvc.perform(get("/v1/agents/").header("X-Netherite-Agent-Key", agentKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/v1/gateway").header("X-Netherite-Agent-Key", agentKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/v1/gateway/execute/").header("X-Netherite-Agent-Key", agentKey))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("BND-4. Path-variant rejections come from the authorization boundary, not a handler")
    void pathVariantRejectionCarriesFailClosedBody() throws Exception {
        mockMvc.perform(get("/v1//agents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));

        mockMvc.perform(get("/v1/agents/").header("X-Netherite-Agent-Key", agentKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
    }
}