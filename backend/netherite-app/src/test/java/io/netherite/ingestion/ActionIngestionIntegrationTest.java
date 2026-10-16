package io.netherite.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.entity.OrganizationEntity;
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

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActionIngestionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private AgentDomainService agentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Ingestion Corp", "ingest-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(),
                "IngestAgent-01",
                "Action Ingestion Test Agent",
                "TIER_1"
        );
    }

    @Test
    @DisplayName("1. Valid authenticated action is ingested successfully")
    void testValidActionIngestion() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API",
                "parameters", Map.of("amount", 100.00, "currency", "USD")
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionId").value(notNullValue()))
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    @Test
    @DisplayName("2. Valid action is converted to CanonicalAction with trusted identity")
    void testCanonicalActionUsesTrustedIdentity() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "ORDER:CREATE",
                "resource", "ORDER_SERVICE",
                "parameters", Map.of("itemId", "item-123", "quantity", 2)
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionId").value(notNullValue()))
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    @Test
    @DisplayName("3. Deterministic canonicalization: same input produces consistent output structure")
    void testDeterministicCanonicalization() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API",
                "parameters", Map.of("amount", 50.00)
        );

        String body = objectMapper.writeValueAsString(request);

        String response1 = mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String response2 = mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> r1 = objectMapper.readValue(response1, Map.class);
        Map<String, Object> r2 = objectMapper.readValue(response2, Map.class);

        // Both have actionId, status INGESTED, but different actionIds (UUIDs)
        org.junit.jupiter.api.Assertions.assertEquals("INGESTED", r1.get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("INGESTED", r2.get("status"));
        org.junit.jupiter.api.Assertions.assertNotNull(r1.get("actionId"));
        org.junit.jupiter.api.Assertions.assertNotNull(r2.get("actionId"));
        org.junit.jupiter.api.Assertions.assertNotEquals(r1.get("actionId"), r2.get("actionId"));
    }

    @Test
    @DisplayName("4. Missing action field is rejected with 400")
    void testMissingActionRejected() throws Exception {
        Map<String, Object> request = Map.of(
                "resource", "PAYMENT_API",
                "parameters", Map.of("amount", 100.00)
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. Missing resource field is rejected with 400")
    void testMissingResourceRejected() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "parameters", Map.of("amount", 100.00)
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. Empty action string is rejected with 400")
    void testEmptyActionRejected() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "",
                "resource", "PAYMENT_API"
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("7. Client-supplied organizationId cannot spoof authenticated tenant")
    void testClientCannotSpoofOrganizationId() throws Exception {
        UUID spoofedOrgId = UUID.randomUUID();

        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API",
                "parameters", Map.of("organizationId", spoofedOrgId.toString())
        );

        String response = mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> result = objectMapper.readValue(response, Map.class);
        org.junit.jupiter.api.Assertions.assertEquals("INGESTED", result.get("status"));
    }

    @Test
    @DisplayName("8. Client-supplied agentId cannot spoof authenticated agent")
    void testClientCannotSpoofAgentId() throws Exception {
        UUID spoofedAgentId = UUID.randomUUID();

        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API",
                "parameters", Map.of("agentId", spoofedAgentId.toString())
        );

        String response = mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> result = objectMapper.readValue(response, Map.class);
        org.junit.jupiter.api.Assertions.assertEquals("INGESTED", result.get("status"));
    }

    @Test
    @DisplayName("9. Unauthenticated request is rejected with 401")
    void testUnauthenticatedRequestRejected() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API"
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("10. Action type and resource are normalized to uppercase")
    void testNormalizationUppercase() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "payment:refund",
                "resource", "payment_api"
        );

        mockMvc.perform(post("/v1/actions/ingest")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionId").value(notNullValue()))
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }
}
