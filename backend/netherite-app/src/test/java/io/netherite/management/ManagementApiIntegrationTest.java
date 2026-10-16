package io.netherite.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.api.model.*;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.*;
import io.netherite.domain.repository.*;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Workstream 1: Control Plane Management API Integration Tests.
 * Covers all management endpoints: agents, resources, bindings, policies, audit, users.
 * Verifies tenant isolation, CRUD operations, and secret leakage prevention.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
class ManagementApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentDomainService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private PolicyVersionRepository policyVersionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentApiKeyRepository apiKeyRepository;
    @Autowired private AgentResourceBindingRepository bindingRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrganizationEntity organization;
    private AgentEntity agent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Mgmt Corp", "mgmt-" + UUID.randomUUID().toString().substring(0, 8));

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(organization.getId().toString())
                .principalType("USER")
                .build());

        AgentDomainService.AgentRegistrationResult regResult = agentDomainService.registerAgent(
                organization.getId(), "MgmtAgent", "Management Test Agent", "TIER_1");
        agent = regResult.agent();

        resource = resourceDomainService.createResource(
                organization.getId(), "MGMT_API", ConnectorType.HTTP_REST,
                "https://mgmt.example.com", null, SensitivityLevel.MEDIUM, "Mgmt API");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // =================== AGENT MANAGEMENT ===================

    @Test
    @DisplayName("A1. Create agent via HTTP - returns full API key ONCE")
    void testCreateAgent() throws Exception {
        mockMvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NewAgent\",\"description\":\"A new agent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewAgent"))
                .andExpect(jsonPath("$.organizationId").value(organization.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.fullApiKey").isNotEmpty())
                .andExpect(jsonPath("$.keyIdentifier").isNotEmpty());
    }

    @Test
    @DisplayName("A2. List agents returns only current org's agents")
    void testListAgents() throws Exception {
        mockMvc.perform(get("/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("MgmtAgent"));
    }

    @Test
    @DisplayName("A3. Get agent by ID")
    void testGetAgent() throws Exception {
        mockMvc.perform(get("/v1/agents/" + agent.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MgmtAgent"))
                .andExpect(jsonPath("$.trustTier").value("TIER_1"));
    }

    @Test
    @DisplayName("A4. Get agent not found returns 404")
    void testGetAgentNotFound() throws Exception {
        UUID fakeId = UUID.randomUUID();
        mockMvc.perform(get("/v1/agents/" + fakeId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A5. Update agent name and trust tier")
    void testUpdateAgent() throws Exception {
        mockMvc.perform(put("/v1/agents/" + agent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"UpdatedAgent\",\"trustTier\":\"TIER_3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("UpdatedAgent"))
                .andExpect(jsonPath("$.trustTier").value("TIER_3"));
    }

    @Test
    @DisplayName("A6. Deactivate agent sets status to INACTIVE")
    void testDeactivateAgent() throws Exception {
        mockMvc.perform(post("/v1/agents/" + agent.getId() + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("A7. Activate agent sets status to ACTIVE")
    void testActivateAgent() throws Exception {
        agent.setStatus("INACTIVE");

        mockMvc.perform(post("/v1/agents/" + agent.getId() + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("A8. List API keys for agent")
    void testListAgentKeys() throws Exception {
        mockMvc.perform(get("/v1/agents/" + agent.getId() + "/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].keyIdentifier").isNotEmpty());
    }

    @Test
    @DisplayName("A9. Create new API key for agent")
    void testCreateApiKey() throws Exception {
        mockMvc.perform(post("/v1/agents/" + agent.getId() + "/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullApiKey").isNotEmpty())
                .andExpect(jsonPath("$.keyIdentifier").isNotEmpty());
    }

    @Test
    @DisplayName("A10. Revoke API key")
    void testRevokeApiKey() throws Exception {
        AgentApiKeyEntity key = apiKeyRepository.findAllByAgentIdAndOrganizationId(agent.getId(), organization.getId()).get(0);

        mockMvc.perform(post("/v1/agents/" + agent.getId() + "/keys/" + key.getId() + "/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API key revoked successfully"));
    }

    // =================== RESOURCE MANAGEMENT ===================

    @Test
    @DisplayName("R1. Create resource via HTTP")
    void testCreateResource() throws Exception {
        mockMvc.perform(post("/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NewResource\",\"connectorType\":\"HTTP_REST\",\"baseUrl\":\"https://new.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewResource"))
                .andExpect(jsonPath("$.connectorType").value("HTTP_REST"));
    }

    @Test
    @DisplayName("R2. List resources returns only current org's resources")
    void testListResources() throws Exception {
        mockMvc.perform(get("/v1/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("MGMT_API"));
    }

    @Test
    @DisplayName("R3. Get resource by ID")
    void testGetResource() throws Exception {
        mockMvc.perform(get("/v1/resources/" + resource.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MGMT_API"));
    }

    @Test
    @DisplayName("R4. Update resource name")
    void testUpdateResource() throws Exception {
        mockMvc.perform(put("/v1/resources/" + resource.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"UpdatedResource\",\"baseUrl\":\"https://updated.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("UpdatedResource"));
    }

    @Test
    @DisplayName("R5. Deactivate resource")
    void testDeactivateResource() throws Exception {
        mockMvc.perform(post("/v1/resources/" + resource.getId() + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("R6. Activate resource")
    void testActivateResource() throws Exception {
        resource.setStatus("INACTIVE");

        mockMvc.perform(post("/v1/resources/" + resource.getId() + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // =================== BINDING MANAGEMENT ===================

    @Test
    @DisplayName("B1. Create binding via HTTP")
    void testCreateBinding() throws Exception {
        mockMvc.perform(post("/v1/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agent.getId() + "\",\"resourceId\":\"" + resource.getId() + "\",\"allowedActions\":[\"READ\",\"WRITE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agent.getId().toString()))
                .andExpect(jsonPath("$.resourceId").value(resource.getId().toString()))
                .andExpect(jsonPath("$.allowedActions").isArray())
                .andExpect(jsonPath("$.allowedActions.length()").value(2));
    }

    @Test
    @DisplayName("B2. List bindings returns only current org's bindings")
    void testListBindings() throws Exception {
        bindingDomainService.bindAgentToResource(organization.getId(), agent.getId(), resource.getId(), List.of("READ"));

        mockMvc.perform(get("/v1/bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("B3. Get binding by ID")
    void testGetBinding() throws Exception {
        AgentResourceBindingEntity binding = bindingDomainService.bindAgentToResource(
                organization.getId(), agent.getId(), resource.getId(), List.of("READ"));

        mockMvc.perform(get("/v1/bindings/" + binding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions[0]").value("READ"));
    }

    @Test
    @DisplayName("B4. Update binding allowed actions")
    void testUpdateBinding() throws Exception {
        AgentResourceBindingEntity binding = bindingDomainService.bindAgentToResource(
                organization.getId(), agent.getId(), resource.getId(), List.of("READ"));

        mockMvc.perform(put("/v1/bindings/" + binding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedActions\":[\"READ\",\"WRITE\",\"DELETE\"],\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions.length()").value(3));
    }

    @Test
    @DisplayName("B5. Deactivate binding via DELETE")
    void testDeleteBinding() throws Exception {
        AgentResourceBindingEntity binding = bindingDomainService.bindAgentToResource(
                organization.getId(), agent.getId(), resource.getId(), List.of("READ"));

        mockMvc.perform(delete("/v1/bindings/" + binding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Binding deactivated"));
    }

    // =================== POLICY MANAGEMENT ===================

    @Test
    @DisplayName("P1. Create policy via HTTP")
    void testCreatePolicy() throws Exception {
        mockMvc.perform(post("/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TestPolicy\",\"description\":\"A test policy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TestPolicy"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("P2. List policies")
    void testListPolicies() throws Exception {
        policyRepository.save(PolicyEntity.builder()
                .organizationId(organization.getId())
                .name("ExistingPolicy")
                .status("DRAFT")
                .build());

        mockMvc.perform(get("/v1/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("P3. Get policy by ID")
    void testGetPolicy() throws Exception {
        PolicyEntity policy = policyRepository.save(PolicyEntity.builder()
                .organizationId(organization.getId())
                .name("GetPolicy")
                .status("DRAFT")
                .build());

        mockMvc.perform(get("/v1/policies/" + policy.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GetPolicy"));
    }

    @Test
    @DisplayName("P4. Update policy name")
    void testUpdatePolicy() throws Exception {
        PolicyEntity policy = policyRepository.save(PolicyEntity.builder()
                .organizationId(organization.getId())
                .name("OldName")
                .status("DRAFT")
                .build());

        mockMvc.perform(put("/v1/policies/" + policy.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NewName\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewName"));
    }

    @Test
    @DisplayName("P5. Create policy version")
    void testCreatePolicyVersion() throws Exception {
        PolicyEntity policy = policyRepository.save(PolicyEntity.builder()
                .organizationId(organization.getId())
                .name("VersionPolicy")
                .status("DRAFT")
                .build());

        mockMvc.perform(post("/v1/policies/" + policy.getId() + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regoCode\":\"package netherite\\nallow = true\",\"naturalLanguagePrompt\":\"Allow all\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regoCode").value("package netherite\nallow = true"))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("P6. Activate policy version")
    void testActivatePolicyVersion() throws Exception {
        PolicyEntity policy = policyRepository.save(PolicyEntity.builder()
                .organizationId(organization.getId())
                .name("ActivatePolicy")
                .status("DRAFT")
                .build());

        PolicyVersionEntity version = policyVersionRepository.save(PolicyVersionEntity.builder()
                .policyId(policy.getId())
                .versionNumber(1)
                .regoCode("package netherite\nallow = true")
                .status("DRAFT")
                .build());

        mockMvc.perform(post("/v1/policies/" + policy.getId() + "/versions/" + version.getId() + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        PolicyEntity updated = policyRepository.findById(policy.getId()).orElseThrow();
        assertEquals("ACTIVE", updated.getStatus());
        assertEquals(version.getId(), updated.getActiveVersionId());
    }

    @Test
    @DisplayName("P7. Policy not found returns 404")
    void testGetPolicyNotFound() throws Exception {
        mockMvc.perform(get("/v1/policies/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // =================== AUDIT LOG MANAGEMENT ===================

    @Test
    @DisplayName("AU1. List audit logs returns current org's logs")
    void testListAuditLogs() throws Exception {
        auditLogRepository.save(AuditLogEntity.builder()
                .organizationId(organization.getId())
                .requestId("req-" + UUID.randomUUID().toString().substring(0, 8))
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(agent.getId())
                .actionType("EXECUTE")
                .targetResource("PAYMENT_API")
                .decision("ALLOW")
                .prevRecordHash("0".repeat(64))
                .currentRecordHash("a".repeat(64))
                .build());

        mockMvc.perform(get("/v1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("AU2. Get audit log by request ID")
    void testGetAuditLogByRequestId() throws Exception {
        String requestId = "req-" + UUID.randomUUID().toString().substring(0, 8);
        auditLogRepository.save(AuditLogEntity.builder()
                .organizationId(organization.getId())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(agent.getId())
                .actionType("EXECUTE")
                .targetResource("PAYMENT_API")
                .decision("ALLOW")
                .prevRecordHash("0".repeat(64))
                .currentRecordHash("a".repeat(64))
                .build());

        mockMvc.perform(get("/v1/audit/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(requestId))
                .andExpect(jsonPath("$[0].decision").value("ALLOW"));
    }

    @Test
    @DisplayName("AU3. Audit log not found returns 404")
    void testGetAuditLogNotFound() throws Exception {
        mockMvc.perform(get("/v1/audit/nonexistent"))
                .andExpect(status().isNotFound());
    }

    // =================== USER MANAGEMENT ===================

    @Test
    @DisplayName("U1. List users returns current org's users")
    void testListUsers() throws Exception {
        userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("test@example.com")
                .fullName("Test User")
                .status("ACTIVE")
                .build());

        mockMvc.perform(get("/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("test@example.com"));
    }

    @Test
    @DisplayName("U2. Get user by ID")
    void testGetUser() throws Exception {
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("get@example.com")
                .fullName("Get User")
                .status("ACTIVE")
                .build());

        mockMvc.perform(get("/v1/users/" + user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("get@example.com"))
                // WS6: the role field is replaced by the designation flag.
                .andExpect(jsonPath("$.authorizedPerson").value(false));
    }

    @Test
    @DisplayName("U3. User not found returns 404")
    void testGetUserNotFound() throws Exception {
        mockMvc.perform(get("/v1/users/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // =================== TENANT ISOLATION ===================

    @Test
    @DisplayName("T1. Cross-tenant agent access is blocked")
    void testCrossTenantAgentAccessBlocked() throws Exception {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));
        AgentDomainService.AgentRegistrationResult otherAgent = agentDomainService.registerAgent(
                otherOrg.getId(), "OtherAgent", "Other Agent", "TIER_1");

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(otherOrg.getId().toString())
                .principalType("USER")
                .build());

        mockMvc.perform(get("/v1/agents/" + agent.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T2. Cross-tenant resource access is blocked")
    void testCrossTenantResourceAccessBlocked() throws Exception {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(otherOrg.getId().toString())
                .principalType("USER")
                .build());

        mockMvc.perform(get("/v1/resources/" + resource.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T3. Cross-tenant binding access is blocked")
    void testCrossTenantBindingAccessBlocked() throws Exception {
        AgentResourceBindingEntity binding = bindingDomainService.bindAgentToResource(
                organization.getId(), agent.getId(), resource.getId(), List.of("READ"));

        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(otherOrg.getId().toString())
                .principalType("USER")
                .build());

        mockMvc.perform(get("/v1/bindings/" + binding.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T4. Cross-tenant policy access is blocked")
    void testCrossTenantPolicyAccessBlocked() throws Exception {
        PolicyEntity policy = policyRepository.save(PolicyEntity.builder()
                .organizationId(organization.getId())
                .name("SecretPolicy")
                .status("DRAFT")
                .build());

        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(otherOrg.getId().toString())
                .principalType("USER")
                .build());

        mockMvc.perform(get("/v1/policies/" + policy.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T5. Cross-tenant audit log access is blocked")
    void testCrossTenantAuditAccessBlocked() throws Exception {
        String requestId = "req-sec-" + UUID.randomUUID().toString().substring(0, 8);
        auditLogRepository.save(AuditLogEntity.builder()
                .organizationId(organization.getId())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(agent.getId())
                .actionType("EXECUTE")
                .targetResource("PAYMENT_API")
                .decision("ALLOW")
                .prevRecordHash("0".repeat(64))
                .currentRecordHash("a".repeat(64))
                .build());

        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(otherOrg.getId().toString())
                .principalType("USER")
                .build());

        mockMvc.perform(get("/v1/audit/" + requestId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T6. Cross-tenant user access is blocked")
    void testCrossTenantUserAccessBlocked() throws Exception {
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("secret@example.com")
                .fullName("Secret User")
                .status("ACTIVE")
                .build());

        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(otherOrg.getId().toString())
                .principalType("USER")
                .build());

        mockMvc.perform(get("/v1/users/" + user.getId()))
                .andExpect(status().isNotFound());
    }

    // =================== SECRET LEAKAGE PREVENTION ===================

    @Test
    @DisplayName("S1. Agent GET does not return secret tokens")
    void testAgentGetNoSecretLeakage() throws Exception {
        mockMvc.perform(get("/v1/agents/" + agent.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullApiKey").doesNotExist())
                .andExpect(jsonPath("$.secretToken").doesNotExist());
    }

    @Test
    @DisplayName("S2. Agent list does not return secret tokens")
    void testAgentListNoSecretLeakage() throws Exception {
        mockMvc.perform(get("/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullApiKey").doesNotExist())
                .andExpect(jsonPath("$[0].secretToken").doesNotExist());
    }

    @Test
    @DisplayName("S3. Resource GET does not return credentials")
    void testResourceNoSecretLeakage() throws Exception {
        mockMvc.perform(get("/v1/resources/" + resource.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encryptedCredentials").doesNotExist());
    }

    @Test
    @DisplayName("S4. Agent key list does not return secret hash")
    void testKeyListNoSecretLeakage() throws Exception {
        mockMvc.perform(get("/v1/agents/" + agent.getId() + "/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].secretHash").doesNotExist());
    }
}
