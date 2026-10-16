package io.netherite.phase7;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.domain.entity.AuditLogEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AuditLogRepository;
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

/**
 * Phase 7 Enterprise Hardening & Production Readiness Tests.
 * Verifies security boundaries, tenant isolation, fail-closed behavior, exception handling,
 * and production-readiness controls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class Phase7IntegrationTest {

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
        organization = organizationService.createOrganization("Phase7 Corp", "p7-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "Phase7Agent", "Phase 7 Test Agent", "TIER_1");
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
    @DisplayName("1. Tenant isolation - human cannot access another org's approval ticket via HTTP")
    void testCrossTenantApprovalAccessBlocked() throws Exception {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();
        io.netherite.context.model.DecisionContext context = buildContext(action);
        ApprovalTicket ticket = approvalService.createTicket(context, List.of("base-policy"));

        io.netherite.domain.entity.UserEntity otherApprover = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(otherOrg.getId())
                .email("other-approver-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Other Approver")
                .status("ACTIVE")
                .build());
        // Designate the caller in ITS OWN organization so authorization passes and
        // the request reaches the controller. That keeps the assertion meaningful:
        // a fully authorized person from another tenant must still get 404, not
        // the other tenant's ticket. Without this the filter would return 403
        // and the test would no longer prove anything about tenant isolation.
        otherOrg.setAuthorizedPersonUserId(otherApprover.getId());
        organizationRepository.save(otherOrg);
        organizationRepository.flush();
        String rawOtherApproverToken = "p7-other-approver-" + UUID.randomUUID().toString();
        userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(otherApprover.getId())
                        .organizationId(otherOrg.getId())
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawOtherApproverToken))
                        .expiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(24)))
                        .build());

        mockMvc.perform(get("/v1/approvals/" + ticket.getTicketId())
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", rawOtherApproverToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("2. Approval endpoint requires authentication - unauthenticated returns 401")
    void testApprovalEndpointRequiresAuth() throws Exception {
        mockMvc.perform(get("/v1/approvals/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3. Execute endpoint returns 401 without credentials")
    void testExecuteEndpointRequiresAuth() throws Exception {
        mockMvc.perform(post("/v1/actions/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAYMENT:REFUND\",\"resource\":\"PAYMENT_API\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("4. DENY decision prevents connector execution")
    void testDenyDecisionPreventsExecution() {
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
    @DisplayName("5. HUMAN_APPROVAL cannot be bypassed - must go through approval flow")
    void testHumanApprovalCannotBeBypassed() {
        registeredAgent.agent().setTrustTier("TIER_3");
        CanonicalAction action = buildTestAction();

        io.netherite.common.exception.NetheriteException ex = org.junit.jupiter.api.Assertions.assertThrows(
                io.netherite.common.exception.NetheriteException.class,
                () -> connectorExecutionService.execute(action));

        assertTrue(ex.getDetails().contains("Ticket ID:"));
        assertEquals(403, ex.getErrorCode().getHttpStatus());
    }

    @Test
    @DisplayName("6. SSRF guard blocks internal addresses")
    void testSsrfGuardBlocksInternal() {
        io.netherite.connectors.ssrf.SsrfGuardValidatorImpl validator =
                new io.netherite.connectors.ssrf.SsrfGuardValidatorImpl(List.of(
                        "0.0.0.0/8", "127.0.0.0/8", "10.0.0.0/8",
                        "172.16.0.0/12", "192.168.0.0/16", "169.254.169.254/32"));

        assertFalse(validator.isUriPermitted(java.net.URI.create("http://127.0.0.1/admin")));
        assertFalse(validator.isUriPermitted(java.net.URI.create("http://10.0.0.1/internal")));
        assertFalse(validator.isUriPermitted(java.net.URI.create("http://169.254.169.254/metadata")));
        assertFalse(validator.isUriPermitted(java.net.URI.create("http://192.168.1.1/router")));
        assertFalse(validator.isUriPermitted(java.net.URI.create("http://172.16.0.1/private")));
    }

    @Test
    @DisplayName("7. SSRF guard fails closed on null/invalid input")
    void testSsrfGuardFailClosed() {
        io.netherite.connectors.ssrf.SsrfGuardValidatorImpl validator =
                new io.netherite.connectors.ssrf.SsrfGuardValidatorImpl(List.of("127.0.0.0/8"));

        assertFalse(validator.isUriPermitted(null));
        assertFalse(validator.isUriPermitted(java.net.URI.create("ftp://evil.com/")));
        assertFalse(validator.isUriPermitted(java.net.URI.create("https://this-does-not-exist-12345.invalid/")));
    }

    @Test
    @DisplayName("8. Idempotency prevents duplicate execution")
    void testIdempotencyPreventsDuplicates() {
        String idempotencyKey = "idem-phase7-" + UUID.randomUUID();

        boolean claimed = idempotencyStore.tryClaim(
                organization.getId().toString(), idempotencyKey, Duration.ofMinutes(30));
        assertTrue(claimed);

        boolean secondClaim = idempotencyStore.tryClaim(
                organization.getId().toString(), idempotencyKey, Duration.ofMinutes(30));
        assertFalse(secondClaim);
    }

    @Test
    @DisplayName("9. Idempotency is tenant-isolated")
    void testIdempotencyTenantIsolated() {
        String key = "shared-phase7-" + UUID.randomUUID();
        OrganizationEntity otherOrg = organizationService.createOrganization("Other", "oth-" + UUID.randomUUID().toString().substring(0, 8));

        assertTrue(idempotencyStore.tryClaim(organization.getId().toString(), key, Duration.ofMinutes(30)));
        assertTrue(idempotencyStore.tryClaim(otherOrg.getId().toString(), key, Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("10. Distributed lock prevents concurrent execution")
    void testLockPreventsConcurrentExecution() {
        String lockKey = "lock-phase7-" + UUID.randomUUID();

        Optional<io.netherite.state.model.DistributedLock> lock1 = lockManager.tryAcquireLock(
                lockKey, Duration.ofSeconds(1), Duration.ofSeconds(30));
        assertTrue(lock1.isPresent());
        assertTrue(lock1.get().isHeld());

        Optional<io.netherite.state.model.DistributedLock> lock2 = lockManager.tryAcquireLock(
                lockKey, Duration.ofSeconds(1), Duration.ofSeconds(30));
        assertFalse(lock2.isPresent());

        lock1.get().unlock();
        assertFalse(lock1.get().isHeld());

        Optional<io.netherite.state.model.DistributedLock> lock3 = lockManager.tryAcquireLock(
                lockKey, Duration.ofSeconds(1), Duration.ofSeconds(30));
        assertTrue(lock3.isPresent());
    }

    @Test
    @DisplayName("11. Audit redaction prevents secret leakage")
    void testAuditRedactionPreventsSecretLeakage() {
        Map<String, Object> raw = Map.of(
                "password", "hunter2",
                "creditCard", "4111-1111-1111-1111",
                "ssn", "123-45-6789",
                "email", "test@example.com",
                "amount", 99.99);

        Map<String, Object> redacted = dataRedactionService.redactParameters(orgId(), raw);

        assertFalse(redacted.toString().contains("hunter2"));
        assertFalse(redacted.toString().contains("4111-1111-1111-1111"));
        assertFalse(redacted.toString().contains("123-45-6789"));
        assertFalse(redacted.toString().contains("test@example.com"));
        assertEquals(99.99, redacted.get("amount"));
    }

    @Test
    @DisplayName("12. Secrets never appear in audit logs")
    void testSecretsNotInAuditLogs() {
        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of("secretKey", "super-secret-value-12345"))
                .timestamp(java.time.Instant.now())
                .build();

        try {
            connectorExecutionService.execute(action);
        } catch (Exception e) {
            // expected
        }

        List<AuditLogEntity> records = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertFalse(records.isEmpty());
        String allAuditData = records.toString();
        assertFalse(allAuditData.contains("super-secret-value-12345"));
    }

    @Test
    @DisplayName("13. Global exception handler prevents stack trace leaks")
    void testGlobalExceptionHandlerPreventsStackLeaks() throws Exception {
        mockMvc.perform(get("/v1/approvals/invalid-not-a-uuid")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", createApproverSession())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("14. HUMAN_APPROVAL via execute service creates auditable pending ticket")
    void testExecuteServiceCreatesAuditTrail() {
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

        List<AuditLogEntity> auditRecords = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertFalse(auditRecords.isEmpty());
    }

    @Test
    @DisplayName("15. HTTP approval rejects invalid ticketId format gracefully")
    void testApprovalRejectsInvalidTicketId() throws Exception {
        mockMvc.perform(get("/v1/approvals/not-a-valid-uuid")
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", createApproverSession())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("16. HTTP approval returns 404 for non-existent ticket")
    void testApprovalReturns404ForNonexistent() throws Exception {
        UUID fakeId = UUID.randomUUID();
        mockMvc.perform(get("/v1/approvals/" + fakeId)
                        .cookie(new jakarta.servlet.http.Cookie("neth_sid", createApproverSession())))
                .andExpect(status().isNotFound());
    }

    private String createApproverSession() {
        io.netherite.domain.entity.UserEntity approver = userRepository.save(io.netherite.domain.entity.UserEntity.builder()
                .organizationId(organization.getId())
                .email("approver-p7-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("P7 Approver")
                .status("ACTIVE")
                .build());
        // WS6: the organization's designated authorized person is the only
        // principal that may decide approval tickets.
        organization.setAuthorizedPersonUserId(approver.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();
        String rawSessionToken = "p7-approver-" + UUID.randomUUID().toString();
        userSessionRepository.save(
                io.netherite.domain.entity.UserSessionEntity.builder()
                        .userId(approver.getId())
                        .organizationId(organization.getId())
                        .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(rawSessionToken))
                        .expiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(24)))
                        .build());
        return rawSessionToken;
    }

    @Test
    @DisplayName("17. Error responses use generic messages without internal details")
    void testErrorResponsesSanitized() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("action", "", "resource", ""));
        mockMvc.perform(post("/v1/actions/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("18. Idempotency markCompleted and getRecord roundtrip")
    void testIdempotencyRoundtrip() {
        String key = "roundtrip-" + UUID.randomUUID();
        String cachedJson = "{\"successful\":true,\"statusCode\":200}";

        idempotencyStore.markCompleted(orgId(), key, cachedJson, Duration.ofMinutes(30));

        Optional<IdempotencyStore.IdempotencyRecord> record = idempotencyStore.getRecord(orgId(), key);
        assertTrue(record.isPresent());
        assertEquals(IdempotencyStore.IdempotencyState.COMPLETED, record.get().state());
        assertEquals(cachedJson, record.get().cachedResponseJson());
    }

    @Test
    @DisplayName("19. Audit records include proper hash chain")
    void testAuditHashChainIntegrity() {
        for (int i = 0; i < 3; i++) {
            CanonicalAction action = CanonicalAction.builder()
                    .actionId(UUID.randomUUID().toString())
                    .organizationId(organization.getId().toString())
                    .agentId(registeredAgent.agent().getId().toString())
                    .actionType("AUDIT:CHAIN:" + i)
                    .targetResource("")
                    .parameters(Map.of("seq", i))
                    .timestamp(java.time.Instant.now())
                    .build();
            try {
                connectorExecutionService.execute(action);
            } catch (Exception e) {
                // expected DENY
            }
        }

        List<AuditLogEntity> records = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertTrue(records.size() >= 3);

        for (AuditLogEntity record : records) {
            assertNotNull(record.getCurrentRecordHash());
            assertEquals(64, record.getCurrentRecordHash().length());
            assertNotNull(record.getPrevRecordHash());
        }
    }

    @Test
    @DisplayName("20. Tenant isolation - two orgs cannot see each other's audit logs via repository")
    void testAuditLogsTenantIsolated() {
        OrganizationEntity otherOrg = organizationService.createOrganization("Audit Other", "au-" + UUID.randomUUID().toString().substring(0, 8));

        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("TENANT:TEST")
                .targetResource("")
                .parameters(Map.of())
                .timestamp(java.time.Instant.now())
                .build();
        try { connectorExecutionService.execute(action); } catch (Exception e) {}

        List<AuditLogEntity> orgRecords = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        List<AuditLogEntity> otherRecords = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(otherOrg.getId());

        assertFalse(orgRecords.isEmpty());
        assertTrue(otherRecords.isEmpty());
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

    private io.netherite.context.model.DecisionContext buildContext(CanonicalAction action) {
        return io.netherite.context.model.DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of())
                .stateFacts(Map.of())
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(java.time.Instant.now())
                .build();
    }

    private String orgId() {
        return organization.getId().toString();
    }
}
