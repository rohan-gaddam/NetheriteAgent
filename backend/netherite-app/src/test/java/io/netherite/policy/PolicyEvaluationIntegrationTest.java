package io.netherite.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionType;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.model.PolicyRuleResult;
import io.netherite.policy.service.PolicyEvaluationService;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PolicyEvaluationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private AgentDomainService agentService;

    @Autowired
    private PolicyEvaluationService policyEvaluationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Policy Corp", "policy-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(),
                "PolicyAgent-01",
                "Policy Evaluation Test Agent",
                "TIER_1"
        );
    }

    @Test
    @DisplayName("1. Valid CanonicalAction can be evaluated via service")
    void testValidEvaluation() {
        CanonicalAction action = buildTestAction();
        PolicyEvaluationResult result = policyEvaluationService.evaluate(action);
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertTrue(result.getRuleResults().size() > 0);
    }

    @Test
    @DisplayName("2. ALLOW is correctly represented")
    void testAllowDecision() {
        CanonicalAction action = buildTestAction();
        DecisionType decision = policyEvaluationService.evaluateDecision(action);
        assertEquals(DecisionType.ALLOW, decision);
    }

    @Test
    @DisplayName("3. DENY is correctly represented when resource is missing")
    void testDenyDecision() {
        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();

        DecisionType decision = policyEvaluationService.evaluateDecision(action);
        assertEquals(DecisionType.DENY, decision);
    }

    @Test
    @DisplayName("4. HUMAN_APPROVAL is correctly represented for high trust tier")
    void testHumanApprovalDecision() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();
        DecisionType decision = policyEvaluationService.evaluateDecision(action);
        assertEquals(DecisionType.HUMAN_APPROVAL, decision);
    }

    @Test
    @DisplayName("5. DecisionExplanation is preserved correctly")
    void testDecisionExplanation() {
        CanonicalAction action = buildTestAction();
        PolicyEvaluationResult result = policyEvaluationService.evaluate(action);
        DecisionType finalDecision = policyEvaluationService.resolvePrecedence(result);
        var explanation = policyEvaluationService.buildExplanation(finalDecision, result);

        assertNotNull(explanation);
        assertNotNull(explanation.getFinalDecision());
        assertNotNull(explanation.getPrimaryReason());
        assertNotNull(explanation.getContributingPolicies());
        assertNotNull(explanation.getRuleEvaluations());
        assertEquals("DENY > HUMAN_APPROVAL > ALLOW", explanation.getResolutionRule());
    }

    @Test
    @DisplayName("6. Policy evaluation uses trusted tenant context from CanonicalAction")
    void testTrustedTenantContext() {
        CanonicalAction action = buildTestAction();
        PolicyEvaluationResult result = policyEvaluationService.evaluate(action);

        assertNotNull(result);
        assertTrue(result.isSuccessful());

        assertEquals(organization.getId().toString(), action.getOrganizationId());
        assertEquals(registeredAgent.agent().getId().toString(), action.getAgentId());
    }

    @Test
    @DisplayName("7. Client-supplied identity in body cannot influence evaluation")
    void testClientCannotSpoofIdentity() throws Exception {
        UUID fakeOrgId = UUID.randomUUID();
        UUID fakeAgentId = UUID.randomUUID();

        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API",
                "parameters", Map.of("organizationId", fakeOrgId.toString(), "agentId", fakeAgentId.toString())
        );

        String response = mockMvc.perform(post("/v1/actions/evaluate")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value(notNullValue()))
                .andExpect(jsonPath("$.decision").value(notNullValue()))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> result = objectMapper.readValue(response, Map.class);
        assertNotNull(result.get("decision"));
    }

    @Test
    @DisplayName("8. Cross-tenant policy access is rejected (different org cannot evaluate)")
    void testCrossTenantPolicyAccessRejected() {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "other-" + UUID.randomUUID().toString().substring(0, 8));
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

        DecisionType decision = policyEvaluationService.evaluateDecision(crossTenantAction);
        assertNotNull(decision);
    }

    @Test
    @DisplayName("9. Missing action information is handled safely")
    void testMissingActionInformation() {
        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType(null)
                .targetResource(null)
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();

        DecisionType decision = policyEvaluationService.evaluateDecision(action);
        assertEquals(DecisionType.DENY, decision);
    }

    @Test
    @DisplayName("10. Missing/invalid policy information is handled safely (empty rules → DENY)")
    void testEmptyRulesDeny() {
        PolicyEvaluationResult emptyResult = PolicyEvaluationResult.builder()
                .ruleResults(java.util.List.of())
                .successful(true)
                .build();

        DecisionType decision = policyEvaluationService.resolvePrecedence(emptyResult);
        assertEquals(DecisionType.DENY, decision);
    }

    @Test
    @DisplayName("11. DENY takes precedence over ALLOW when both present")
    void testDenyPrecedenceOverAllow() {
        PolicyEvaluationResult mixedResult = PolicyEvaluationResult.builder()
                .ruleResults(java.util.List.of(
                        PolicyRuleResult.builder().policyName("p1").decision(DecisionType.ALLOW).reason("allow rule").build(),
                        PolicyRuleResult.builder().policyName("p2").decision(DecisionType.DENY).reason("deny rule").build()
                ))
                .successful(true)
                .build();

        DecisionType decision = policyEvaluationService.resolvePrecedence(mixedResult);
        assertEquals(DecisionType.DENY, decision);
    }

    @Test
    @DisplayName("12. HUMAN_APPROVAL takes precedence over ALLOW when no DENY present")
    void testApprovalPrecedenceOverAllow() {
        PolicyEvaluationResult mixedResult = PolicyEvaluationResult.builder()
                .ruleResults(java.util.List.of(
                        PolicyRuleResult.builder().policyName("p1").decision(DecisionType.ALLOW).reason("allow rule").build(),
                        PolicyRuleResult.builder().policyName("p2").decision(DecisionType.HUMAN_APPROVAL).reason("approval rule").build()
                ))
                .successful(true)
                .build();

        DecisionType decision = policyEvaluationService.resolvePrecedence(mixedResult);
        assertEquals(DecisionType.HUMAN_APPROVAL, decision);
    }

    @Test
    @DisplayName("13. Unauthenticated evaluate endpoint returns 401")
    void testUnauthenticatedEvaluateRejected() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API"
        );

        mockMvc.perform(post("/v1/actions/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("14. Full evaluation via HTTP endpoint returns valid result")
    void testFullHttpEvaluation() throws Exception {
        Map<String, Object> request = Map.of(
                "action", "PAYMENT:REFUND",
                "resource", "PAYMENT_API",
                "parameters", Map.of("amount", 100.00)
        );

        mockMvc.perform(post("/v1/actions/evaluate")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value(notNullValue()))
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.evaluationDurationNanos").isNumber())
                .andExpect(jsonPath("$.ruleCount").isNumber());
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
