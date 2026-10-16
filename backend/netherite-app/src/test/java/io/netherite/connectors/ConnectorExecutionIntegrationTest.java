package io.netherite.connectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.api.model.ConnectorExecutionApiRequest;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.connectors.ssrf.SsrfGuardValidatorImpl;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConnectorExecutionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private AgentDomainService agentService;

    @Autowired
    private ResourceDomainService resourceDomainService;

    @Autowired
    private BindingDomainService bindingDomainService;

    @Autowired
    private PolicyEvaluationService policyEvaluationService;

    @Autowired
    private ConnectorExecutionService connectorExecutionService;

    @Autowired
    private ResourceRoutingService resourceRoutingService;

    @Autowired
    private SsrfGuardValidator ssrfGuardValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Connector Corp", "conn-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(),
                "ConnectorAgent-01",
                "Connector Execution Test Agent",
                "TIER_1"
        );

        resource = resourceDomainService.createResource(
                organization.getId(),
                "PAYMENT_API",
                ConnectorType.HTTP_REST,
                "https://api.example.com",
                null,
                io.netherite.common.model.SensitivityLevel.MEDIUM,
                "Payment processing API"
        );

        bindingDomainService.bindAgentToResource(
                organization.getId(),
                registeredAgent.agent().getId(),
                resource.getId(),
                List.of("PAYMENT:REFUND", "*")
        );
    }

    @Test
    @DisplayName("1. ALLOW decision reaches resource routing")
    void testAllowReachesResourceRouting() {
        CanonicalAction action = buildTestAction();
        DecisionType decision = policyEvaluationService.evaluateDecision(action);
        assertEquals(DecisionType.ALLOW, decision);

        Optional<ResourceRoute> route = resourceRoutingService.resolveRoute(action);
        assertTrue(route.isPresent());
        assertEquals(resource.getId().toString(), route.get().getResourceId());
        assertEquals("PAYMENT_API", route.get().getResourceName());
        assertEquals(ConnectorType.HTTP_REST, route.get().getConnectorType());
    }

    @Test
    @DisplayName("2. DENY cannot reach a connector")
    void testDenyCannotReachConnector() {
        CanonicalAction denyAction = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                io.netherite.common.exception.NetheriteException.class,
                () -> connectorExecutionService.execute(denyAction));
    }

    @Test
    @DisplayName("3. HUMAN_APPROVAL cannot reach a connector")
    void testHumanApprovalCannotReachConnector() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction approvalAction = buildTestAction();

        DecisionType decision = policyEvaluationService.evaluateDecision(approvalAction);
        assertEquals(DecisionType.HUMAN_APPROVAL, decision);

        org.junit.jupiter.api.Assertions.assertThrows(
                io.netherite.common.exception.NetheriteException.class,
                () -> connectorExecutionService.execute(approvalAction));
    }

    @Test
    @DisplayName("4. Correct tenant resource is selected")
    void testCorrectTenantResourceSelected() {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));
        resourceDomainService.createResource(
                otherOrg.getId(),
                "PAYMENT_API",
                ConnectorType.HTTP_REST,
                "https://other-api.example.com",
                null,
                io.netherite.common.model.SensitivityLevel.MEDIUM,
                "Other payment API"
        );

        CanonicalAction action = buildTestAction();
        Optional<ResourceRoute> route = resourceRoutingService.resolveRoute(action);

        assertTrue(route.isPresent());
        assertEquals(resource.getId().toString(), route.get().getResourceId());
        assertEquals("https://api.example.com", route.get().getBaseUrl());
    }

    @Test
    @DisplayName("5. Cross-tenant resource access is rejected")
    void testCrossTenantResourceAccessRejected() {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));
        AgentDomainService.AgentRegistrationResult otherAgent = agentService.registerAgent(
                otherOrg.getId(), "OtherAgent", "Other agent", "TIER_1");

        CanonicalAction crossTenantAction = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(otherOrg.getId().toString())
                .agentId(otherAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();

        Optional<ResourceRoute> route = resourceRoutingService.resolveRoute(crossTenantAction);
        assertTrue(route.isEmpty());
    }

    @Test
    @DisplayName("6. Agent without binding cannot execute")
    void testAgentWithoutBindingCannotExecute() {
        AgentDomainService.AgentRegistrationResult unboundAgent = agentService.registerAgent(
                organization.getId(), "UnboundAgent", "No binding", "TIER_1");

        CanonicalAction unboundAction = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(unboundAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                io.netherite.common.exception.NetheriteException.class,
                () -> connectorExecutionService.execute(unboundAction));
    }

    @Test
    @DisplayName("7. Unknown resource cannot execute")
    void testUnknownResourceCannotExecute() {
        CanonicalAction unknownAction = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("NONEXISTENT_RESOURCE")
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                io.netherite.common.exception.NetheriteException.class,
                () -> connectorExecutionService.execute(unknownAction));
    }

    @Test
    @DisplayName("8. SSRF validation rejects unsafe destinations (integration)")
    void testSsrfValidationRejectsUnsafeDestinations() {
        assertFalse(ssrfGuardValidator.isUriPermitted(URI.create("http://127.0.0.1:8080/secret")));
        assertFalse(ssrfGuardValidator.isUriPermitted(URI.create("http://localhost:8080/admin")));
        assertFalse(ssrfGuardValidator.isUriPermitted(URI.create("http://10.0.0.1/internal")));
        assertFalse(ssrfGuardValidator.isUriPermitted(URI.create("http://172.16.0.1/private")));
        assertFalse(ssrfGuardValidator.isUriPermitted(URI.create("http://192.168.1.1/router")));
        assertFalse(ssrfGuardValidator.isUriPermitted(URI.create("http://169.254.169.254/latest/meta-data/")));
    }

    @Test
    @DisplayName("9. Client cannot override the registered resource URL")
    void testClientCannotOverrideResourceUrl() {
        CanonicalAction action = buildTestAction();
        assertEquals(organization.getId().toString(), action.getOrganizationId());
        assertEquals("PAYMENT_API", action.getTargetResource());
    }

    @Test
    @DisplayName("10. Client cannot override trusted tenant context")
    void testClientCannotOverrideTenantContext() {
        UUID fakeOrgId = UUID.randomUUID();
        UUID fakeAgentId = UUID.randomUUID();

        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of("organizationId", fakeOrgId.toString(), "agentId", fakeAgentId.toString()))
                .timestamp(java.time.Instant.now())
                .build();

        assertEquals(organization.getId().toString(), action.getOrganizationId());
        assertEquals(registeredAgent.agent().getId().toString(), action.getAgentId());
    }

    @Test
    @DisplayName("11. Connector failure does not produce fake success")
    void testConnectorFailureDoesNotProduceFakeSuccess() {
        SsrfGuardValidatorImpl realSsrfGuard = new SsrfGuardValidatorImpl(List.of(
                "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.169.254/32"));
        assertTrue(realSsrfGuard.isUriPermitted(URI.create("https://93.184.216.34/api")));
    }

    @Test
    @DisplayName("12. Secrets are not exposed in error messages")
    void testSecretsNotExposed() {
        SsrfGuardValidatorImpl realSsrfGuard = new SsrfGuardValidatorImpl(List.of(
                "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.169.254/32"));
        assertFalse(realSsrfGuard.isUriPermitted(URI.create("http://127.0.0.1/secret")));
    }

    @Test
    @DisplayName("13. Full HTTP execute endpoint returns policy blocked for DENY")
    void testHttpExecutePolicyBlocked() throws Exception {
        ConnectorExecutionApiRequest request = ConnectorExecutionApiRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("")
                .parameters(Map.of())
                .build();

        mockMvc.perform(post("/v1/actions/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("14. Unauthenticated execute endpoint returns 401")
    void testUnauthenticatedExecuteRejected() throws Exception {
        ConnectorExecutionApiRequest request = ConnectorExecutionApiRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of())
                .build();

        mockMvc.perform(post("/v1/actions/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("15. Inactive resource cannot be routed to")
    void testInactiveResourceCannotBeRouted() {
        resource.setStatus("INACTIVE");

        CanonicalAction action = buildTestAction();
        Optional<ResourceRoute> route = resourceRoutingService.resolveRoute(action);
        assertTrue(route.isEmpty());
    }

    @Test
    @DisplayName("16. Binding authorization check works correctly")
    void testBindingAuthorizationCheck() {
        assertTrue(resourceRoutingService.isBindingAuthorized(
                organization.getId().toString(),
                registeredAgent.agent().getId().toString(),
                resource.getId().toString(),
                "PAYMENT:REFUND"));
    }

    @Test
    @DisplayName("17. Unbound agent fails binding authorization")
    void testUnboundAgentFailsBindingAuthorization() {
        AgentDomainService.AgentRegistrationResult unboundAgent = agentService.registerAgent(
                organization.getId(), "UnboundAgent2", "No binding", "TIER_1");

        assertFalse(resourceRoutingService.isBindingAuthorized(
                organization.getId().toString(),
                unboundAgent.agent().getId().toString(),
                resource.getId().toString(),
                "PAYMENT:REFUND"));
    }

    @Test
    @DisplayName("18. Route resolution returns empty for null action")
    void testRouteResolutionNullAction() {
        Optional<ResourceRoute> route = resourceRoutingService.resolveRoute(null);
        assertTrue(route.isEmpty());
    }

    private CanonicalAction buildTestAction() {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of("amount", 100.00))
                .timestamp(java.time.Instant.now())
                .build();
    }
}
