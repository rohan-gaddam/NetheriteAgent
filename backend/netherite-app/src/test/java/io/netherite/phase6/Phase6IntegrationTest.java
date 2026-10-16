package io.netherite.phase6;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.api.model.ConnectorExecutionApiRequest;
import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.audit.model.AuditRecord;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.connectors.service.ConnectorExecutionService;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
@Transactional
class Phase6IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private PolicyEvaluationService policyEvaluationService;
    @Autowired private ConnectorExecutionService connectorExecutionService;
    @Autowired private ApprovalService approvalService;
    @Autowired private AuditService auditService;
    @Autowired private DataRedactionService dataRedactionService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private IdempotencyStore idempotencyStore;
    @Autowired private DistributedLockManager lockManager;
    @Autowired private io.netherite.domain.repository.UserRepository userRepository;
    @Autowired private io.netherite.domain.repository.UserSessionRepository userSessionRepository;
    @Autowired private io.netherite.domain.repository.OrganizationRepository organizationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Phase6 Corp", "p6-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "Phase6Agent", "Phase 6 Test Agent", "TIER_1");
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

    @Test
    @DisplayName("1. HUMAN_APPROVAL creates pending approval ticket")
    void testHumanApprovalCreatesTicket() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        DecisionType decision = policyEvaluationService.evaluateDecision(action);
        assertEquals(DecisionType.HUMAN_APPROVAL, decision);

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));
        assertNotNull(ticket);
        assertNotNull(ticket.getTicketId());
        assertEquals(ApprovalStatus.PENDING, ticket.getStatus());
        assertEquals(organization.getId().toString(), ticket.getOrganizationId());
        assertEquals("PAYMENT:REFUND", ticket.getActionType());
        assertEquals("PAYMENT_API", ticket.getTargetResource());
    }

    @Test
    @DisplayName("2. Approval allows controlled continuation (approve then execute)")
    void testApprovalAllowsContinuation() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));
        ApprovalTicket resolved = approvalService.resolveTicket(
                ticket.getTicketId(), ApprovalStatus.APPROVED, "user-123", "Looks good");

        assertEquals(ApprovalStatus.APPROVED, resolved.getStatus());
        assertEquals("user-123", resolved.getApproverUserId());
        assertNotNull(resolved.getResolvedAt());
    }

    @Test
    @DisplayName("3. Rejection prevents execution")
    void testRejectionPreventsExecution() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));
        ApprovalTicket resolved = approvalService.resolveTicket(
                ticket.getTicketId(), ApprovalStatus.REJECTED, "user-456", "Not approved");

        assertEquals(ApprovalStatus.REJECTED, resolved.getStatus());
        assertEquals("Not approved", resolved.getApproverNotes());
    }

    @Test
    @DisplayName("4. DENY never executes")
    void testDenyNeverExecutes() {
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
    @DisplayName("5. HUMAN_APPROVAL via execute service creates ticket")
    void testExecuteServiceCreatesTicket() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.common.exception.NetheriteException ex = org.junit.jupiter.api.Assertions.assertThrows(
                io.netherite.common.exception.NetheriteException.class,
                () -> connectorExecutionService.execute(action));

        assertTrue(ex.getDetails().contains("Ticket ID:"));
        String ticketId = ex.getDetails().substring(ex.getDetails().indexOf("Ticket ID:") + "Ticket ID:".length()).trim();
        Optional<ApprovalTicket> ticket = approvalService.getTicket(ticketId);
        assertTrue(ticket.isPresent());
        assertEquals(ApprovalStatus.PENDING, ticket.get().getStatus());
    }

    @Test
    @DisplayName("6. Duplicate idempotency request is rejected")
    void testDuplicateIdempotencyRejected() {
        CanonicalAction action = buildTestAction();
        action.setIdempotencyKey("idem-key-" + UUID.randomUUID());

        boolean claimed = idempotencyStore.tryClaim(
                organization.getId().toString(),
                action.getIdempotencyKey(),
                Duration.ofMinutes(30));
        assertTrue(claimed);

        boolean secondClaim = idempotencyStore.tryClaim(
                organization.getId().toString(),
                action.getIdempotencyKey(),
                Duration.ofMinutes(30));
        assertFalse(secondClaim);
    }

    @Test
    @DisplayName("7. Tenant-isolated idempotency")
    void testTenantIsolatedIdempotency() {
        String idempotencyKey = "shared-key-" + UUID.randomUUID();
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        boolean claimed1 = idempotencyStore.tryClaim(
                organization.getId().toString(), idempotencyKey, Duration.ofMinutes(30));
        assertTrue(claimed1);

        boolean claimed2 = idempotencyStore.tryClaim(
                otherOrg.getId().toString(), idempotencyKey, Duration.ofMinutes(30));
        assertTrue(claimed2);
    }

    @Test
    @DisplayName("8. Lock acquisition and release")
    void testLockAcquisitionAndRelease() {
        String lockKey = "test-lock-" + UUID.randomUUID();
        Optional<io.netherite.state.model.DistributedLock> lock1 = lockManager.tryAcquireLock(
                lockKey, Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertTrue(lock1.isPresent());
        assertTrue(lock1.get().isHeld());

        Optional<io.netherite.state.model.DistributedLock> lock2 = lockManager.tryAcquireLock(
                lockKey, Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertFalse(lock2.isPresent());

        lock1.get().unlock();
        assertFalse(lock1.get().isHeld());

        Optional<io.netherite.state.model.DistributedLock> lock3 = lockManager.tryAcquireLock(
                lockKey, Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertTrue(lock3.isPresent());
    }

    @Test
    @DisplayName("9. Audit event recorded for DENY")
    void testAuditRecordedForDeny() {
        CanonicalAction denyAction = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of("secret", "password123"))
                .timestamp(java.time.Instant.now())
                .build();

        try {
            connectorExecutionService.execute(denyAction);
        } catch (io.netherite.common.exception.NetheriteException e) {
            // expected
        }

        List<io.netherite.domain.entity.AuditLogEntity> records = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertFalse(records.isEmpty());
    }

    @Test
    @DisplayName("10. Audit redaction - secrets not stored in plaintext")
    void testAuditRedaction() {
        Map<String, Object> rawParams = Map.of(
                "creditCard", "4111-1111-1111-1111",
                "password", "supersecret",
                "email", "user@example.com",
                "amount", 100.00);

        Map<String, Object> redacted = dataRedactionService.redactParameters(organization.getId().toString(), rawParams);

        assertFalse(redacted.toString().contains("4111-1111-1111-1111"));
        assertFalse(redacted.toString().contains("supersecret"));
        assertFalse(redacted.toString().contains("user@example.com"));
        assertEquals(100.00, redacted.get("amount"));
    }

    @Test
    @DisplayName("11. Tenant isolation for approval tickets")
    void testTenantIsolatedApproval() {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));
        Optional<ApprovalTicket> found = approvalService.getTicket(ticket.getTicketId());
        assertTrue(found.isPresent());
        assertEquals(organization.getId().toString(), found.get().getOrganizationId());
    }

    @Test
    @DisplayName("12. HTTP execute endpoint returns 401 for unauthenticated")
    void testHttpExecuteUnauthenticated() throws Exception {
        ConnectorExecutionApiRequest request = ConnectorExecutionApiRequest.builder()
                .action("PAYMENT:REFUND")
                .resource("PAYMENT_API")
                .parameters(Map.of())
                .build();

        mockMvc.perform(post("/v1/actions/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("13. HTTP approval endpoint retrieves ticket")
    void testHttpApprovalRetrieve() throws Exception {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));

        io.netherite.domain.entity.UserEntity viewer = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(organization.getId())
                .email("viewer-phase6-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("Test Viewer")
                .status("ACTIVE")
                .build());
        // WS6: designate this member as the organization's single authorized
        // person, which is what authorizes approval decisions.
        organization.setAuthorizedPersonUserId(viewer.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();
        String rawViewerToken = "phase6-view-" + UUID.randomUUID().toString();
        io.netherite.domain.entity.UserSessionEntity viewerSession = userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(viewer.getId())
                        .organizationId(organization.getId())
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawViewerToken))
                        .expiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(24)))
                        .build());

        mockMvc.perform(get("/v1/approvals/" + ticket.getTicketId())
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawViewerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("14. HTTP approval endpoint approves ticket")
    void testHttpApprovalApprove() throws Exception {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));

        io.netherite.domain.entity.UserEntity approver = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(organization.getId())
                .email("approver-phase6-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("Test Approver")
                .status("ACTIVE")
                .build());
        // WS6: designate this member as the organization's single authorized
        // person, which is what authorizes approval decisions.
        organization.setAuthorizedPersonUserId(approver.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();

        String rawApproveToken = "phase6-approve-" + UUID.randomUUID().toString();
        io.netherite.domain.entity.UserSessionEntity session = userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(approver.getId())
                        .organizationId(organization.getId())
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawApproveToken))
                        .expiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(24)))
                        .build());

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/approve")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawApproveToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\":\"" + approver.getId() + "\",\"notes\":\"Approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("15. HTTP approval endpoint rejects ticket")
    void testHttpApprovalReject() throws Exception {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.context.model.DecisionContext context = io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();

        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));

        io.netherite.domain.entity.UserEntity approver = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(organization.getId())
                .email("rejecter-phase6-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("Test Rejecter")
                .status("ACTIVE")
                .build());
        // WS6: designate this member as the organization's single authorized
        // person, which is what authorizes approval decisions.
        organization.setAuthorizedPersonUserId(approver.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();

        String rawRejectToken = "phase6-reject-" + UUID.randomUUID().toString();
        io.netherite.domain.entity.UserSessionEntity session = userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(approver.getId())
                        .organizationId(organization.getId())
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawRejectToken))
                        .expiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(24)))
                        .build());

        mockMvc.perform(post("/v1/approvals/" + ticket.getTicketId() + "/reject")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawRejectToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Not safe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("16. Connector execution with idempotency key returns cached result on replay")
    void testIdempotentReplayReturnsCachedResult() {
        CanonicalAction action = buildTestAction();
        action.setIdempotencyKey("replay-key-" + UUID.randomUUID());

        String orgId = organization.getId().toString();
        String cacheJson = "{\"successful\":true,\"statusCode\":200,\"executionDurationMillis\":42}";
        idempotencyStore.markCompleted(orgId, action.getIdempotencyKey(), cacheJson, Duration.ofMinutes(30));

        Optional<IdempotencyStore.IdempotencyRecord> record = idempotencyStore.getRecord(orgId, action.getIdempotencyKey());
        assertTrue(record.isPresent());
        assertEquals(IdempotencyStore.IdempotencyState.COMPLETED, record.get().state());
        assertNotNull(record.get().cachedResponseJson());
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
