package io.netherite.approval;

import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.context.model.DecisionContext;
import io.netherite.security.context.TenantContextHolder;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalStateSafetyTest {

    @Autowired MockMvc mockMvc;
    @Autowired ApprovalRequestRepository approvalRequestRepository;
    @Autowired ApprovalService approvalService;
    @Autowired OrganizationService organizationService;
    @Autowired AgentDomainService agentService;
    @Autowired ResourceDomainService resourceDomainService;
    @Autowired BindingDomainService bindingDomainService;
    @Autowired io.netherite.domain.repository.UserRepository userRepository;
    @Autowired io.netherite.domain.repository.UserSessionRepository userSessionRepository;
    @Autowired io.netherite.domain.repository.OrganizationRepository organizationRepository;

    private UUID lastCreatedUserId;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("StateSafety Corp", "ss-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "StateSafetyAgent", "State Safety Test Agent", "TIER_1");
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
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /**
     * Creates a session for a member of {@code orgId}.
     *
     * <p>When {@code designate} is true the member becomes the organization's
     * single designated authorized person, which is what permits it to decide
     * approval tickets under WS6. When false it is an ordinary member whose
     * approval requests must be refused.</p>
     */
    private String createUserSession(UUID orgId, boolean designate) {
        io.netherite.domain.entity.UserEntity user = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(orgId)
                .email("safety-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("Safety Approver")
                .status("ACTIVE")
                .build());
        lastCreatedUserId = user.getId();

        if (designate) {
            io.netherite.domain.entity.OrganizationEntity org = organizationRepository.findById(orgId).orElseThrow();
            org.setAuthorizedPersonUserId(user.getId());
            organizationRepository.save(org);
            organizationRepository.flush();
        }

        String rawToken = "safety-" + UUID.randomUUID().toString();
        userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(user.getId())
                        .organizationId(orgId)
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawToken))
                        .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                        .build());

        return rawToken;
    }

    private ApprovalTicket createPendingTicket() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        DecisionContext context = DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();

        return approvalService.createTicket(context, List.of("base-policy"));
    }

    private CanonicalAction buildTestAction() {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource(resource.getId().toString())
                .parameters(Map.of("amount", 100, "currency", "USD"))
                .idempotencyKey("test-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .build();
    }

    @Test
    @DisplayName("1. Duplicate approve rejected - second approve returns error")
    void testDuplicateApproveRejected() throws Exception {
        ApprovalTicket ticket = createPendingTicket();
        String sessionToken = createUserSession(organization.getId(), true);

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + UUID.randomUUID() + "\",\"notes\":\"Approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + UUID.randomUUID() + "\",\"notes\":\"Approved again\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"));
    }

    @Test
    @DisplayName("2. Approve after reject rejected")
    void testApproveAfterRejectRejected() throws Exception {
        ApprovalTicket ticket = createPendingTicket();
        String sessionToken = createUserSession(organization.getId(), true);

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/reject")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Not safe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + UUID.randomUUID() + "\",\"notes\":\"Now approved\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"));
    }

    @Test
    @DisplayName("3. Reject after approve rejected")
    void testRejectAfterApproveRejected() throws Exception {
        ApprovalTicket ticket = createPendingTicket();
        String sessionToken = createUserSession(organization.getId(), true);

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + UUID.randomUUID() + "\",\"notes\":\"Approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/reject")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Now rejected\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_INVALID_PARAMETERS"));
    }

    @Test
    @DisplayName("4. Approve after expiration rejected")
    void testApproveAfterExpirationRejected() throws Exception {
        ApprovalTicket ticket = createPendingTicket();

        ApprovalRequestEntity entity = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        entity.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        approvalRequestRepository.save(entity);

        String sessionToken = createUserSession(organization.getId(), true);

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + UUID.randomUUID() + "\",\"notes\":\"Late approval\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("ERR_TICKET_EXPIRED"));
    }

    @Test
    @DisplayName("6. Approver identity is server-derived; a forged body value cannot be attributed")
    void testApproverIdentityIsServerDerived() throws Exception {
        ApprovalTicket ticket = createPendingTicket();
        String sessionToken = createUserSession(organization.getId(), true);
        UUID sessionUserId = lastCreatedUserId;
        String forgedApproverId = UUID.randomUUID().toString();

        // The attorney-supplied approverUserId must be ignored: identity comes from
        // the authenticated session, so one user can never record another's decision.
        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + forgedApproverId + "\",\"notes\":\"Approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approverUserId").value(sessionUserId.toString()));

        ApprovalRequestEntity entity = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        assertEquals(sessionUserId.toString(), entity.getApproverUserIdentifier(),
                "Stored approver must be the authenticated user, not the claimed body value");
        assertFalse(forgedApproverId.equals(entity.getApproverUserIdentifier()),
                "A forged approverUserId must never be persisted");
    }

    @Test
    @DisplayName("5. Reject after expiration rejected")
    void testRejectAfterExpirationRejected() throws Exception {
        ApprovalTicket ticket = createPendingTicket();

        ApprovalRequestEntity entity = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        entity.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        approvalRequestRepository.save(entity);

        String sessionToken = createUserSession(organization.getId(), true);

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/reject")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Late rejection\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("ERR_TICKET_EXPIRED"));
    }
}
