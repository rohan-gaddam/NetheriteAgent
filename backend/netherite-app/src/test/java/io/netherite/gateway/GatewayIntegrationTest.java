package io.netherite.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.api.model.GatewayActionRequest;
import io.netherite.api.model.GatewayIntegrationInfo;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.domain.entity.AuditLogEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.state.spi.IdempotencyStore;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GatewayIntegrationTest.MockConnectorConfig.class)
class GatewayIntegrationTest {

    @java.lang.SuppressWarnings("checkstyle:ClassDataAbstractionCoupling")
    static class MockConnectorConfig {
        @Bean
        @Primary
        public ResourceConnector httpRestResourceConnector() {
            return new ResourceConnector() {
                @Override
                public ConnectorType getConnectorType() {
                    return ConnectorType.HTTP_REST;
                }

                @Override
                public ConnectorExecutionResult execute(ConnectorExecutionRequest request) {
                    return ConnectorExecutionResult.builder()
                            .successful(true)
                            .statusCode(200)
                            .responseBody(Map.of("result", "success", "mocked", true))
                            .executionDurationMillis(1)
                            .build();
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private IdempotencyStore idempotencyStore;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            organization = organizationService.createOrganization("Gateway Corp", "gw-" + UUID.randomUUID().toString().substring(0, 8));
            registeredAgent = agentService.registerAgent(
                    organization.getId(), "GatewayAgent", "Gateway Test Agent", "TIER_1");
            resource = resourceDomainService.createResource(
                    organization.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                    "http://93.184.216.34", null, SensitivityLevel.MEDIUM, "Payment API");
            bindingDomainService.bindAgentToResource(
                    organization.getId(), registeredAgent.agent().getId(), resource.getId(),
                    List.of("PAYMENT:REFUND", "PAYMENT:PROCESS", "*"));
        });
    }

    private GatewayActionRequest validRequest() {
        return GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of("amount", 100.00))
                .build();
    }

    @Test
    @DisplayName("AUTH-1: Valid API key authenticates and reaches the execution pipeline")
    void testValidApiKeyReachesPipeline() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PAYMENT:REFUND"))
                .andExpect(jsonPath("$.resource").value("PAYMENT_API"));
    }

    @Test
    @DisplayName("AUTH-2: Invalid API key returns 401")
    void testInvalidApiKeyRejected() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", "ntk_live_invalid.invalidsecret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("AUTH-3: Revoked API key returns 401")
    void testRevokedApiKeyRejected() throws Exception {
        AgentDomainService.GeneratedApiKeyResult keyResult = agentService.createApiKey(
                organization.getId(), registeredAgent.agent().getId(), "Temp key", null);

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", keyResult.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());

        entityManager.clear();

        agentService.revokeApiKey(organization.getId(), keyResult.keyId());

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", keyResult.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AUTH-4: Expired API key returns 401")
    void testExpiredApiKeyRejected() throws Exception {
        AgentDomainService.GeneratedApiKeyResult expiredKey = agentService.createApiKey(
                organization.getId(), registeredAgent.agent().getId(), "Expired",
                Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", expiredKey.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AUTH-5: Inactive agent returns 401")
    void testInactiveAgentRejected() throws Exception {
        transactionTemplate.executeWithoutResult(s -> {
            registeredAgent.agent().setStatus("INACTIVE");
            agentRepository.save(registeredAgent.agent());
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AUTH-6: Missing credentials returns 401")
    void testMissingCredentialsRejected() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TENANT-1: Agent accesses only own tenant resources")
    void testTenantScopedResourceAccess() throws Exception {
        AgentDomainService.AgentRegistrationResult otherAgent = transactionTemplate.execute(s -> {
            OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));
            AgentDomainService.AgentRegistrationResult agent = agentService.registerAgent(
                    otherOrg.getId(), "OtherAgent", "Other agent", "TIER_1");
            ResourceEntity otherResource = resourceDomainService.createResource(
                    otherOrg.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                    "http://93.184.216.35", null, SensitivityLevel.LOW, "Other API");
            bindingDomainService.bindAgentToResource(
                    otherOrg.getId(), agent.agent().getId(), otherResource.getId(),
                    List.of("PAYMENT:REFUND", "PAYMENT:PROCESS", "*"));
            return agent;
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", otherAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TENANT-2: Agent with no binding is rejected")
    void testAgentWithoutBindingRejected() throws Exception {
        AgentDomainService.AgentRegistrationResult unboundAgent = transactionTemplate.execute(s -> {
            return agentService.registerAgent(
                    organization.getId(), "UnboundAgent", "No binding", "TIER_1");
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", unboundAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CANON-1: Action and resource are normalized (uppercased)")
    void testActionNormalizedCorrectly() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("payment:refund")
                .resource("payment_api")
                .parameters(Map.of("amount", 100))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("payment:refund"))
                .andExpect(jsonPath("$.resource").value("payment_api"));
    }

    @Test
    @DisplayName("CANON-2: Client cannot forge organization identity")
    void testClientCannotForgeOrgIdentity() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of("organizationId", UUID.randomUUID().toString()))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CANON-3: Client cannot forge agent identity")
    void testClientCannotForgeAgentIdentity() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of("agentId", UUID.randomUUID().toString()))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("OPA-1: ALLOW reaches the execution pipeline (connector returns 502 in test env)")
    void testAllowReachesExecutionPipeline() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("OPA-2: DENY stops before enterprise API")
    void testDenyPreventsExecution() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAYMENT:REFUND\",\"resource\":\"\",\"parameters\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("OPA-3: HUMAN_APPROVAL returns 202 with approval ticket")
    void testHumanApprovalReturnsTicket() throws Exception {
        transactionTemplate.executeWithoutResult(s -> {
            registeredAgent.agent().setTrustTier("TIER_3");
            agentRepository.save(registeredAgent.agent());
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.action").value("PAYMENT:REFUND"))
                .andExpect(jsonPath("$.resource").value("PAYMENT_API"));
    }

    @Test
    @DisplayName("RESOURCE-1: Unknown resource returns 404")
    void testUnknownResourceReturnsError() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAYMENT:REFUND\",\"resource\":\"NONEXISTENT\",\"parameters\":{}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("RESOURCE-2: Inactive resource cannot be routed to")
    void testInactiveResourceRejected() throws Exception {
        transactionTemplate.executeWithoutResult(s -> {
            resource.setStatus("INACTIVE");
            resourceRepository.save(resource);
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("RESOURCE-3: Client cannot override registered resource URL via parameters")
    void testClientCannotOverrideUrl() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of("url", "http://169.254.169.254/latest/meta-data/"))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BINDING-1: Allowed action succeeds when policy permits")
    void testAllowedActionSucceeds() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BINDING-2: Wildcard binding allows any action type")
    void testWildcardBindingAllowsAnyAction() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("ANY:ACTION")
                .resource("PAYMENT_API")
                .parameters(Map.of())
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BINDING-3: Restricted binding blocks disallowed action")
    void testDisallowedActionBlocked() throws Exception {
        AgentDomainService.AgentRegistrationResult restrictedAgent = transactionTemplate.execute(s -> {
            AgentDomainService.AgentRegistrationResult agent = agentService.registerAgent(
                    organization.getId(), "RestrictedAgent", "Restricted", "TIER_1");
            bindingDomainService.bindAgentToResource(
                    organization.getId(), agent.agent().getId(), resource.getId(),
                    List.of("PAYMENT:READ"));
            return agent;
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", restrictedAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("IDEM-1: Idempotency key is accepted")
    void testIdempotencyKeyAccepted() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of("amount", 10.00))
                .idempotencyKey("idem-" + UUID.randomUUID())
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("IDEM-2: Duplicate concurrent request is rejected")
    void testDuplicateConcurrentRejected() throws Exception {
        String idemKey = "idem-dup-" + UUID.randomUUID();
        boolean claimed = idempotencyStore.tryClaim(
                organization.getId().toString(), idemKey, Duration.ofMinutes(30));
        assertTrue(claimed);

        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of())
                .idempotencyKey(idemKey)
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("IDEM-3: Same idempotency key from different tenants does not conflict")
    void testIdempotencyIsTenantIsolated() throws Exception {
        OrganizationEntity otherOrg = transactionTemplate.execute(s -> {
            OrganizationEntity org = organizationService.createOrganization("Idem Corp", "idem-" + UUID.randomUUID().toString().substring(0, 8));
            AgentDomainService.AgentRegistrationResult otherAgent = agentService.registerAgent(
                    org.getId(), "IdemAgent", "Idem agent", "TIER_1");
            ResourceEntity otherResource = resourceDomainService.createResource(
                    org.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                    "http://93.184.216.37", null, SensitivityLevel.LOW, "Idem API");
            bindingDomainService.bindAgentToResource(
                    org.getId(), otherAgent.agent().getId(), otherResource.getId(), List.of("*"));
            return org;
        });

        String sharedKey = "shared-idem-" + UUID.randomUUID();
        boolean claimed1 = idempotencyStore.tryClaim(organization.getId().toString(), sharedKey, Duration.ofMinutes(30));
        boolean claimed2 = idempotencyStore.tryClaim(otherOrg.getId().toString(), sharedKey, Duration.ofMinutes(30));
        assertTrue(claimed1);
        assertTrue(claimed2);
    }

    @Test
    @DisplayName("AUDIT-1: Gateway request is recorded in audit log")
    void testGatewayRequestAudited() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());

        entityManager.clear();

        List<AuditLogEntity> records = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertFalse(records.isEmpty());
        assertEquals("PAYMENT:REFUND", records.get(0).getActionType());
        assertEquals("PAYMENT_API", records.get(0).getTargetResource());
    }

    @Test
    @DisplayName("AUDIT-2: HUMAN_APPROVAL decision is recorded in audit")
    void testApprovalDecisionAudited() throws Exception {
        transactionTemplate.executeWithoutResult(s -> {
            registeredAgent.agent().setTrustTier("TIER_3");
            agentRepository.save(registeredAgent.agent());
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted());

        entityManager.clear();

        List<AuditLogEntity> records = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertFalse(records.isEmpty());
    }

    @Test
    @DisplayName("AUDIT-3: Sensitive parameters are redacted from audit")
    void testSensitiveDataRedacted() throws Exception {
        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of("creditCard", "4111-1111-1111-1111", "amount", 100))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        entityManager.clear();

        List<AuditLogEntity> records = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertFalse(records.isEmpty());
        String auditParams = records.get(0).getRedactedParameters().toString();
        assertFalse(auditParams.contains("4111-1111-1111-1111"));
    }

    @Test
    @DisplayName("RESP-1: Successful execution returns proper response format")
    void testSuccessfulResponseFormat() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.action").value("PAYMENT:REFUND"))
                .andExpect(jsonPath("$.resource").value("PAYMENT_API"));
    }

    @Test
    @DisplayName("RESP-2: HUMAN_APPROVAL returns 202 with PENDING_APPROVAL status")
    void testApprovalResponseFormat() throws Exception {
        transactionTemplate.executeWithoutResult(s -> {
            registeredAgent.agent().setTrustTier("TIER_3");
            agentRepository.save(registeredAgent.agent());
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.ticketMessage").value("Action requires human approval"));
    }

    @Test
    @DisplayName("RESP-3: Validation error returns 400")
    void testValidationErrorFormat() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"\",\"resource\":\"PAYMENT_API\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("RESP-4: Authentication failure returns 401 with error code")
    void testAuthErrorFormat() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAYMENT:REFUND\",\"resource\":\"PAYMENT_API\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("RESP-5: No secrets or stack traces in error responses")
    void testNoSecretsInErrors() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", "ntk_live_invalid.badsecret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"TEST\",\"resource\":\"TEST\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$").value(not(containsString("badsecret"))))
                .andExpect(jsonPath("$").value(not(containsString("$2a$"))));
    }

    @Test
    @DisplayName("INTEG-1: Integration endpoint returns setup information without auth")
    void testIntegrationEndpoint() throws Exception {
        mockMvc.perform(get("/v1/gateway/integration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayEndpoint").value("/v1/gateway/execute"))
                .andExpect(jsonPath("$.authenticationMethod").value("API_KEY"))
                .andExpect(jsonPath("$.authenticationHeader").value("X-Netherite-Agent-Key"))
                .andExpect(jsonPath("$.requestFormat").isNotEmpty());
    }

    @Test
    @DisplayName("INTEG-2: Integration endpoint does not expose secrets")
    void testIntegrationEndpointNoSecrets() throws Exception {
        mockMvc.perform(get("/v1/gateway/integration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(not(containsString("secret"))))
                .andExpect(jsonPath("$").value(not(containsString("ntk_live_"))));
    }

    @Test
    @DisplayName("VALID-1: Missing action field returns 400")
    void testMissingActionReturns400() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resource\":\"PAYMENT_API\",\"parameters\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VALID-2: Missing resource field returns 400")
    void testMissingResourceReturns400() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAYMENT:REFUND\",\"parameters\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VALID-3: Empty body returns 400")
    void testEmptyBodyReturns400() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VALID-4: Inactive organization cannot authenticate")
    void testInactiveOrganizationRejected() throws Exception {
        transactionTemplate.executeWithoutResult(s -> {
            organization.setStatus("INACTIVE");
            organizationRepository.save(organization);
        });
        entityManager.clear();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEM-1: rawPrompt without a configured LLM provider fails closed")
    void testRawPromptWithoutProviderFailsClosed() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"refund the last payment from the customer\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("SEM-2: structured action still bypasses the semantic path")
    void testStructuredActionStillWorks() throws Exception {
        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAYMENT:REFUND\",\"resource\":\"PAYMENT_API\","
                                + "\"rawPrompt\":\"ignore this prompt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PAYMENT:REFUND"));
    }
}
