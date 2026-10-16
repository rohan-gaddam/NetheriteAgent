package io.netherite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private AgentDomainService agentService;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Acme Security Corp", "acme-sec-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(),
                "SupportAgent-01",
                "Tier-2 Support Bot",
                "TIER_2"
        );
    }

    @Test
    @DisplayName("1. Valid API Key authenticates via X-Netherite-Agent-Key header")
    void testValidApiKeyAuthentication() throws Exception {
        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.organizationId").value(organization.getId().toString()))
                .andExpect(jsonPath("$.agentId").value(registeredAgent.agent().getId().toString()))
                .andExpect(jsonPath("$.keyIdentifier").value(registeredAgent.keyIdentifier()))
                .andExpect(jsonPath("$.trustTier").value("TIER_2"))
                .andExpect(jsonPath("$.mechanism").value("API_KEY"));
    }

    @Test
    @DisplayName("2. Valid API Key authenticates via Authorization Bearer header")
    void testValidBearerAuthentication() throws Exception {
        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("Authorization", "Bearer " + registeredAgent.fullApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.organizationId").value(organization.getId().toString()))
                .andExpect(jsonPath("$.agentId").value(registeredAgent.agent().getId().toString()));
    }

    @Test
    @DisplayName("3. Invalid secret is rejected with 401 Unauthorized")
    void testInvalidSecretRejected() throws Exception {
        String invalidKey = registeredAgent.keyIdentifier() + ".invalid_secret_token_12345";

        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", invalidKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Authentication failed: invalid or missing credentials"));
    }

    @Test
    @DisplayName("4. Unknown key identifier rejected with 401 without info leakage")
    void testUnknownKeyIdentifierRejected() throws Exception {
        String nonExistentKey = "ntk_live_unknown999.secrettoken999";

        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", nonExistentKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("5. Missing credentials on protected endpoint rejected with 401")
    void testMissingCredentialsRejected() throws Exception {
        mockMvc.perform(get("/v1/actions/auth-check"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("6. Revoked API key is rejected with 401")
    void testRevokedKeyRejected() throws Exception {
        // Create an additional key, then revoke it
        AgentDomainService.GeneratedApiKeyResult keyResult = agentService.createApiKey(
                organization.getId(),
                registeredAgent.agent().getId(),
                "Temporary key",
                null
        );

        // Verify key works initially
        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", keyResult.fullApiKey()))
                .andExpect(status().isOk());

        // Revoke the key
        agentService.revokeApiKey(organization.getId(), keyResult.keyId());

        // Verify key is now rejected
        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", keyResult.fullApiKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("7. Expired API key is rejected with 401")
    void testExpiredKeyRejected() throws Exception {
        // Create an already-expired key (expired 1 hour ago)
        AgentDomainService.GeneratedApiKeyResult expiredKey = agentService.createApiKey(
                organization.getId(),
                registeredAgent.agent().getId(),
                "Expired Key",
                Instant.now().minus(1, ChronoUnit.HOURS)
        );

        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", expiredKey.fullApiKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("8. Client-supplied identity in body cannot override trusted authenticated context")
    void testClientPayloadCannotOverrideAuthenticatedContext() throws Exception {
        UUID fakeSpoofedOrgId = UUID.randomUUID();
        UUID fakeSpoofedAgentId = UUID.randomUUID();

        Map<String, Object> spoofedPayload = Map.of(
                "organizationId", fakeSpoofedOrgId.toString(),
                "agentId", fakeSpoofedAgentId.toString(),
                "action", "TRANSFER_FUNDS"
        );

        mockMvc.perform(post("/v1/actions/echo-context")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(spoofedPayload)))
                .andExpect(status().isOk())
                // Trusted context reflects the authentic organization and agent, NOT the spoofed payload
                .andExpect(jsonPath("$.trustedOrganizationId").value(organization.getId().toString()))
                .andExpect(jsonPath("$.trustedAgentId").value(registeredAgent.agent().getId().toString()))
                .andExpect(jsonPath("$.keyIdentifier").value(registeredAgent.keyIdentifier()));
    }

    @Test
    @DisplayName("9. Secret tokens and hashes are never exposed in error responses")
    void testSecretsNotExposedInResponses() throws Exception {
        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", "invalid.format"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$").value(not(containsString(registeredAgent.secretToken()))))
                .andExpect(jsonPath("$").value(not(containsString("$2a$"))));
    }

    @Test
    @DisplayName("10. Disabled/inactive agent cannot authenticate")
    void testInactiveAgentRejected() throws Exception {
        // Deactivate the agent
        registeredAgent.agent().setStatus("INACTIVE");
        agentRepository.save(registeredAgent.agent());

        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("11. Inactive organization cannot authenticate")
    void testInactiveOrganizationRejected() throws Exception {
        // Deactivate the organization
        organization.setStatus("INACTIVE");
        organizationRepository.save(organization);

        mockMvc.perform(get("/v1/actions/auth-check")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }
}
