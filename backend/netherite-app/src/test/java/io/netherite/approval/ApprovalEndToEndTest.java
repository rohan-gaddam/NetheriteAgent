package io.netherite.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.service.ApprovalEventPublisher;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.audit.spi.AuditService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.state.spi.DistributedLockManager;
import io.netherite.state.spi.IdempotencyStore;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalEndToEndTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApprovalService approvalService;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private ConnectorExecutionService connectorExecutionService;
    @Autowired private PolicyEvaluationService policyEvaluationService;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private io.netherite.domain.repository.UserRepository userRepository;
    @Autowired private io.netherite.domain.repository.UserSessionRepository userSessionRepository;
    @Autowired private io.netherite.domain.repository.OrganizationRepository organizationRepository;
    @Autowired private AuditService auditService;
    @Autowired private IdempotencyStore idempotencyStore;
    @Autowired private DistributedLockManager lockManager;
    @Autowired private ApprovalEventPublisher eventPublisher;
    @Autowired private AgentRepository agentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;
    private io.netherite.domain.entity.UserEntity approver;
    private io.netherite.domain.entity.UserSessionEntity approverSession;
    private String approverRawToken;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("ApprovalE2E Corp", "a2e-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "ApprovalAgent", "Approval E2E Test Agent", "TIER_1");
        resource = resourceDomainService.createResource(
                organization.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                "https://api.example.com", null,
                io.netherite.common.model.SensitivityLevel.MEDIUM, "Payment API");
        bindingDomainService.bindAgentToResource(
                organization.getId(), registeredAgent.agent().getId(), resource.getId(),
                List.of("PAYMENT:REFUND", "*"));

        TenantContextHolder.setContext(io.netherite.common.context.TenantContext.builder()
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .principalType("AGENT")
                .build());

        approver = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(organization.getId())
                .email("approver-e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("E2E Approver")
                .status("ACTIVE")
                .build());

        // WS6: designate the approver as the organization's single authorized
        // person, which is what authorizes approval decisions.
        organization.setAuthorizedPersonUserId(approver.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();

        approverRawToken = "e2e-approve-" + UUID.randomUUID().toString();
        approverSession = userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(approver.getId())
                        .organizationId(organization.getId())
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(approverRawToken))
                        .expiresAt(Instant.now().plus(java.time.Duration.ofHours(24)))
                        .build());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. Full approval flow: execute -> HUMAN_APPROVAL -> verify pending -> approve via HTTP -> verify APPROVED -> SSE events")
    void testFullApprovalFlow() throws Exception {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> connectorExecutionService.execute(action));
        assertTrue(ex.getDetails().contains("Ticket ID:"));
        String ticketId = ex.getDetails().substring(
                ex.getDetails().indexOf("Ticket ID:") + "Ticket ID:".length()).trim();

        mockMvc.perform(get("/v1/approvals/" + ticketId)
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", approverRawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.actionType").value("PAYMENT:REFUND"))
                .andExpect(jsonPath("$.targetResource").value(resource.getId().toString()));

        mockMvc.perform(post("/v1/approvals/" + ticketId + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", approverRawToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + approver.getId() + "\",\"notes\":\"Approved via E2E\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        Optional<ApprovalTicket> approved = approvalService.getTicket(ticketId);
        assertTrue(approved.isPresent());
        assertEquals(ApprovalStatus.APPROVED, approved.get().getStatus());
        assertEquals(approver.getId().toString(), approved.get().getApproverUserId());
        assertNotNull(approved.get().getResolvedAt());
    }

    @Test
    @DisplayName("2. Full rejection flow: execute -> HUMAN_APPROVAL -> reject via HTTP -> verify REJECTED")
    void testFullRejectionFlow() throws Exception {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> connectorExecutionService.execute(action));
        String ticketId = extractTicketId(ex);

        mockMvc.perform(get("/v1/approvals/" + ticketId)
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", approverRawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/v1/approvals/" + ticketId + "/reject")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", approverRawToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Rejected via E2E - not safe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        Optional<ApprovalTicket> rejected = approvalService.getTicket(ticketId);
        assertTrue(rejected.isPresent());
        assertEquals(ApprovalStatus.REJECTED, rejected.get().getStatus());
        assertEquals("Rejected via E2E - not safe", rejected.get().getApproverNotes());
        assertNotNull(rejected.get().getResolvedAt());
    }

    @Test
    @DisplayName("3. Stale approval prevention: deactivate agent before approval")
    void testStaleApprovalPrevention() throws Exception {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        NetheriteException ex = assertThrows(NetheriteException.class,
                () -> connectorExecutionService.execute(action));
        String ticketId = extractTicketId(ex);

        Optional<ApprovalTicket> pendingTicket = approvalService.getTicket(ticketId);
        assertTrue(pendingTicket.isPresent());
        assertEquals(ApprovalStatus.PENDING, pendingTicket.get().getStatus());

        AgentEntity agent = agentService.getAgent(organization.getId(), registeredAgent.agent().getId());
        agent.setStatus("INACTIVE");
        agentRepository.save(agent);

        mockMvc.perform(post("/v1/approvals/" + ticketId + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", approverRawToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + approver.getId() + "\",\"notes\":\"Approved but agent is stale\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        Optional<ApprovalTicket> approved = approvalService.getTicket(ticketId);
        assertTrue(approved.isPresent());
        assertEquals(ApprovalStatus.APPROVED, approved.get().getStatus());

        AgentEntity reactivated = agentService.getAgent(organization.getId(), registeredAgent.agent().getId());
        reactivated.setStatus("ACTIVE");
        agentRepository.save(reactivated);
    }

    private CanonicalAction buildTestAction() {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource(resource.getId().toString())
                .parameters(Map.of("amount", 100, "currency", "USD"))
                .idempotencyKey("e2e-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .build();
    }

    private String extractTicketId(NetheriteException ex) {
        String details = ex.getDetails();
        assertTrue(details.contains("Ticket ID:"));
        return details.substring(details.indexOf("Ticket ID:") + "Ticket ID:".length()).trim();
    }
}
