package io.netherite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ManagementEndpointAuthenticationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AgentDomainService agentDomainService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Auth Test Corp", "auth-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentDomainService.registerAgent(
                organization.getId(), "AuthTestAgent", "Auth test agent", "TIER_1");
    }

    @Test
    @DisplayName("Auth-M1. Unauthenticated GET /v1/agents returns 401")
    void testUnauthenticatedAgentList() throws Exception {
        mockMvc.perform(get("/v1/agents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("Auth-M2. Unauthenticated POST /v1/agents returns 401")
    void testUnauthenticatedAgentCreate() throws Exception {
        mockMvc.perform(post("/v1/agents")
                        .contentType("application/json")
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M3. Unauthenticated GET /v1/agents/{id} returns 401")
    void testUnauthenticatedAgentGet() throws Exception {
        mockMvc.perform(get("/v1/agents/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M4. Unauthenticated GET /v1/resources returns 401")
    void testUnauthenticatedResourceList() throws Exception {
        mockMvc.perform(get("/v1/resources"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M5. Unauthenticated POST /v1/resources returns 401")
    void testUnauthenticatedResourceCreate() throws Exception {
        mockMvc.perform(post("/v1/resources")
                        .contentType("application/json")
                        .content("{\"name\":\"Test\",\"connectorType\":\"HTTP_REST\",\"baseUrl\":\"https://test.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M6. Unauthenticated GET /v1/bindings returns 401")
    void testUnauthenticatedBindingList() throws Exception {
        mockMvc.perform(get("/v1/bindings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M7. Unauthenticated POST /v1/policies returns 401")
    void testUnauthenticatedPolicyCreate() throws Exception {
        mockMvc.perform(post("/v1/policies")
                        .contentType("application/json")
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M8. Unauthenticated GET /v1/policies returns 401")
    void testUnauthenticatedPolicyList() throws Exception {
        mockMvc.perform(get("/v1/policies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M9. Unauthenticated GET /v1/audit returns 401")
    void testUnauthenticatedAuditList() throws Exception {
        mockMvc.perform(get("/v1/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M10. Unauthenticated GET /v1/users returns 401")
    void testUnauthenticatedUserList() throws Exception {
        mockMvc.perform(get("/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M11. Revoked API key is rejected on management endpoint")
    void testRevokedKeyRejectedOnManagement() throws Exception {
        AgentDomainService.GeneratedApiKeyResult newKey = agentDomainService.createApiKey(
                organization.getId(), registeredAgent.agent().getId(), "temp", null);

        agentDomainService.revokeApiKey(organization.getId(), newKey.keyId());

        mockMvc.perform(get("/v1/agents")
                        .header("X-Netherite-Agent-Key", newKey.fullApiKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auth-M12. Valid API key is DENIED on management endpoints (agents are execute-only)")
    void testValidKeyDeniedOnManagementEndpoint() throws Exception {
        mockMvc.perform(get("/v1/agents")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auth-M13. Valid API key is ALLOWED on the agent action surface")
    void testValidKeyAllowedOnActionSurface() throws Exception {
        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    @DisplayName("Auth-M14. The designated authorized person's session IS allowed on management endpoints")
    void testDesignatedPersonSessionAllowedOnManagementEndpoint() throws Exception {
        UserEntity person = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("person-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Designated Person")
                .status("ACTIVE")
                .build());
        // WS6: authorization is by designation, not by role.
        organization.setAuthorizedPersonUserId(person.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();
        String rawM14Token = "m14-session-" + UUID.randomUUID();
        UserSessionEntity session = userSessionRepository.save(UserSessionEntity.builder()
                .userId(person.getId())
                .organizationId(organization.getId())
                .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawM14Token))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());

        mockMvc.perform(get("/v1/agents")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawM14Token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Auth-M15. A non-designated human session is DENIED on management endpoints")
    void testNonDesignatedSessionDeniedOnManagementEndpoint() throws Exception {
        // WS6: an ordinary member of the organization carries no authority of
        // any kind, so every management surface refuses them.
        UserEntity member = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("member-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Ordinary Member")
                .status("ACTIVE")
                .build());
        String rawM15Token = "m15-session-" + UUID.randomUUID();
        UserSessionEntity session = userSessionRepository.save(UserSessionEntity.builder()
                .userId(member.getId())
                .organizationId(organization.getId())
                .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawM15Token))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());

        mockMvc.perform(get("/v1/agents")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawM15Token)))
                .andExpect(status().isForbidden());

        // The audit surface is designated-person-only, exactly like every other
        // governance surface.
        mockMvc.perform(get("/v1/audit")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawM15Token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/dashboard")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawM15Token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/approvals")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawM15Token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auth-M16. Agent API keys are denied the entire management surface")
    void testValidKeyDeniedOnAllManagementSurfaces() throws Exception {
        String key = registeredAgent.fullApiKey();
        mockMvc.perform(get("/v1/agents").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/resources").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/bindings").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/policies").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/users").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/audit").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/control/organizations").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/approvals").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/dashboard").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/dashboard/health/check").header("X-Netherite-Agent-Key", key)).andExpect(status().isForbidden());
    }
}
