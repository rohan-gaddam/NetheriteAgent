package io.netherite.audit;

import io.netherite.audit.model.AuditRecord;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.common.context.TenantContext;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.domain.entity.AuditLogEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import io.netherite.security.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditMultiEventRegressionTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ConnectorExecutionService connectorExecutionService;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private DataRedactionService dataRedactionService;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Audit Regression Corp", "ar-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "AuditAgent", "Audit Regression Agent", "TIER_1");
        resource = resourceDomainService.createResource(
                organization.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                "http://93.184.216.34", null, SensitivityLevel.MEDIUM, "Payment API");
        bindingDomainService.bindAgentToResource(
                organization.getId(), registeredAgent.agent().getId(), resource.getId(),
                List.of("PAYMENT:REFUND", "*"));

        TenantContextHolder.setContext(TenantContext.builder()
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
    @DisplayName("REG-AUDIT-1: EXECUTION_STARTED can be persisted")
    void testExecutionStartedCanBePersisted() {
        String requestId = UUID.randomUUID().toString();

        AuditRecord startedEvent = AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(0)
                .build();

        AuditRecord returned = auditService.record(startedEvent);

        assertNotNull(returned.getAuditId());
        assertEquals("EXECUTION_STARTED", returned.getEventType());
        assertEquals(requestId, returned.getRequestId());
    }

    @Test
    @DisplayName("REG-AUDIT-2: EXECUTION_SUCCEEDED can be persisted with the same requestId")
    void testExecutionSucceededWithSameRequestId() {
        String requestId = UUID.randomUUID().toString();

        AuditRecord startedEvent = AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(0)
                .build();
        auditService.record(startedEvent);

        AuditRecord succeededEvent = AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .redactedExecutionResult(Map.of("result", "success"))
                .executionDurationMillis(150)
                .build();
        AuditRecord returned = auditService.record(succeededEvent);

        assertNotNull(returned.getAuditId());
        assertEquals("EXECUTION_SUCCEEDED", returned.getEventType());
        assertEquals(requestId, returned.getRequestId());
    }

    @Test
    @DisplayName("REG-AUDIT-3: Both audit records exist afterward")
    void testBothRecordsExist() {
        String requestId = UUID.randomUUID().toString();

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(0)
                .build());

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .redactedExecutionResult(Map.of("result", "success"))
                .executionDurationMillis(150)
                .build());

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdAndRequestId(organization.getId(), requestId);

        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(r -> "EXECUTION_STARTED".equals(r.getEventType())));
        assertTrue(records.stream().anyMatch(r -> "EXECUTION_SUCCEEDED".equals(r.getEventType())));
    }

    @Test
    @DisplayName("REG-AUDIT-4: Hash-chain integrity remains valid with multi-event execution")
    void testHashChainIntegrityWithMultiEvent() {
        String requestId = UUID.randomUUID().toString();

        AuditRecord started = auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(0)
                .build());

        AuditRecord succeeded = auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .redactedExecutionResult(Map.of("result", "success"))
                .executionDurationMillis(150)
                .build());

        assertNotNull(started.getCurrentRecordHash());
        assertEquals(64, started.getCurrentRecordHash().length());
        assertNotNull(succeeded.getCurrentRecordHash());
        assertEquals(64, succeeded.getCurrentRecordHash().length());

        assertEquals("0".repeat(64), started.getPrevRecordHash());
        assertEquals(started.getCurrentRecordHash(), succeeded.getPrevRecordHash());

        List<AuditLogEntity> allRecords = auditLogRepository
                .findAllByOrganizationIdInOrder(organization.getId());
        assertTrue(auditService.verifyChainContinuity(
                organization.getId().toString(),
                allRecords.stream().map(this::toRecord).toList()));
    }

    private AuditRecord completedRecord(String requestId, String userId) {
        return auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .agentId(registeredAgent.agent().getId().toString())
                .userId(userId)
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of("amount", "100"))
                .redactedExecutionResult(Map.of("result", "success"))
                .executionDurationMillis(150)
                .build());
    }

    @Test
    @DisplayName("REG-AUDIT-6: Out-of-band mutation of operator identity breaks the chain")
    void testTamperedOperatorIdentityBreaksChain() {
        completedRecord(UUID.randomUUID().toString(), "operator-a");
        completedRecord(UUID.randomUUID().toString(), "operator-a");

        // @Immutable blocks ORM-mediated writes, so tampering surfaces through the
        // database itself (exactly what an attacker with UPDATE privileges, or a
        // leaked admin connection, could do).
        List<AuditLogEntity> rows = auditLogRepository.findAllByOrganizationIdInOrder(organization.getId());
        UUID targetId = rows.get(0).getId();
        int updated = entityManager.createNativeQuery("UPDATE audit_logs SET user_id = 'intruder-account' WHERE id = ?")
                .setParameter(1, targetId)
                .executeUpdate();
        assertEquals(1, updated);
        entityManager.flush();
        entityManager.clear();

        List<AuditLogEntity> tampered = auditLogRepository.findAllByOrganizationIdInOrder(organization.getId());
        assertFalse(auditService.verifyChainContinuity(organization.getId().toString(),
                tampered.stream().map(this::toRecord).toList()),
                "A changed operator identity must break chain verification");
    }

    @Test
    @DisplayName("REG-AUDIT-7: Out-of-band mutation of the decision breaks the chain")
    void testTamperedExecutionResultBreaksChain() {
        completedRecord(UUID.randomUUID().toString(), "operator-a");
        completedRecord(UUID.randomUUID().toString(), "operator-a");

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrganizationIdInOrder(organization.getId());
        UUID targetId = rows.get(0).getId();
        int updated = entityManager.createNativeQuery(
                "UPDATE audit_logs SET decision = 'DENY' WHERE id = ?")
                .setParameter(1, targetId)
                .executeUpdate();
        assertEquals(1, updated);
        entityManager.flush();
        entityManager.clear();

        List<AuditLogEntity> tampered = auditLogRepository.findAllByOrganizationIdInOrder(organization.getId());
        assertFalse(auditService.verifyChainContinuity(organization.getId().toString(),
                tampered.stream().map(this::toRecord).toList()),
                "A changed decision must break chain verification");
    }

    @Test
    @DisplayName("REG-AUDIT-8: Deletion of a middle record breaks consecutive linkage")
    void testDeletedRecordBreaksChain() {
        completedRecord(UUID.randomUUID().toString(), "operator-a");
        String middleRequestId = UUID.randomUUID().toString();
        completedRecord(middleRequestId, "operator-a");
        completedRecord(UUID.randomUUID().toString(), "operator-a");

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrganizationIdInOrder(organization.getId());
        assertEquals(3, rows.size());
        UUID targetId = rows.get(1).getId();
        int deleted = entityManager.createQuery("DELETE FROM AuditLogEntity e WHERE e.id = :id")
                .setParameter("id", targetId)
                .executeUpdate();
        assertEquals(1, deleted);
        entityManager.flush();
        entityManager.clear();

        List<AuditLogEntity> remaining = auditLogRepository.findAllByOrganizationIdInOrder(organization.getId());
        assertEquals(2, remaining.size());
        assertFalse(auditService.verifyChainContinuity(organization.getId().toString(),
                remaining.stream().map(this::toRecord).toList()),
                "Deleting a middle record must break consecutive linkage");
    }

    @Test
    @DisplayName("REG-AUDIT-9: Duplicate (requestId, eventType) within one tenant is rejected")
    void testDuplicateEventRejectedWithinTenant() {
        String requestId = UUID.randomUUID().toString();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String insert = "INSERT INTO audit_logs (id, organization_id, request_id, event_type, agent_id, user_id, "
                + "action_type, target_resource, decision, policy_evaluations, redacted_parameters, "
                + "redacted_execution_result, execution_duration_ms, prev_record_hash, current_record_hash, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, ?, ?, CURRENT_TIMESTAMP)";

        // Raw JDBC (outside the ORM flush path) hits the tenant-scoped unique
        // constraint directly: the duplicate must be rejected by the database.
        java.sql.SQLException duplicateRejected = entityManager.unwrap(org.hibernate.Session.class)
                .doReturningWork(connection -> {
                    try (java.sql.PreparedStatement first = connection.prepareStatement(insert)) {
                        first.setObject(1, id1);
                        first.setObject(2, organization.getId());
                        first.setString(3, requestId);
                        first.setString(4, "EXECUTION_SUCCEEDED");
                        first.setObject(5, registeredAgent.agent().getId());
                        first.setString(6, "operator-a");
                        first.setString(7, "PAYMENT:REFUND");
                        first.setString(8, "PAYMENT_API");
                        first.setString(9, "ALLOW");
                        first.setString(10, "0".repeat(64));
                        first.setString(11, "1".repeat(64));
                        assertEquals(1, first.executeUpdate());
                    }
                    try (java.sql.PreparedStatement second = connection.prepareStatement(insert)) {
                        second.setObject(1, id2);
                        second.setObject(2, organization.getId());
                        second.setString(3, requestId);
                        second.setString(4, "EXECUTION_SUCCEEDED");
                        second.setObject(5, registeredAgent.agent().getId());
                        second.setString(6, "operator-a");
                        second.setString(7, "PAYMENT:REFUND");
                        second.setString(8, "PAYMENT_API");
                        second.setString(9, "ALLOW");
                        second.setString(10, "0".repeat(64));
                        second.setString(11, "2".repeat(64));
                        try {
                            second.executeUpdate();
                            return null;
                        } catch (java.sql.SQLException e) {
                            return e;
                        }
                    }
                });

        assertNotNull(duplicateRejected, "Duplicate (requestId, eventType) must be rejected");
        assertTrue(duplicateRejected.getMessage() != null
                        && duplicateRejected.getMessage().toLowerCase().contains("unique"),
                "Expected a unique-constraint rejection, got: " + duplicateRejected.getMessage());
    }

    @Test
    @DisplayName("REG-AUDIT-5: Tenant isolation - different orgs cannot share requestId+eventType")
    void testTenantIsolationForAuditEvents() {
        OrganizationEntity otherOrg = organizationService.createOrganization(
                "Other Audit Corp", "oa-" + UUID.randomUUID().toString().substring(0, 8));
        String requestId = UUID.randomUUID().toString();

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(100)
                .build());

        List<AuditLogEntity> orgRecords = auditLogRepository
                .findAllByOrganizationIdAndRequestId(organization.getId(), requestId);
        List<AuditLogEntity> otherRecords = auditLogRepository
                .findAllByOrganizationIdAndRequestId(otherOrg.getId(), requestId);

        assertEquals(1, orgRecords.size());
        assertTrue(otherRecords.isEmpty());
    }

    @Test
    @DisplayName("REG-AUDIT-6: Redaction remains intact for multi-event executions")
    void testRedactionIntactForMultiEvent() {
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> rawParams = Map.of(
                "creditCard", "4111-1111-1111-1111",
                "password", "supersecret",
                "email", "user@example.com",
                "amount", 100.00);

        Map<String, Object> redacted = dataRedactionService.redactParameters(
                organization.getId().toString(), rawParams);

        assertFalse(redacted.toString().contains("4111-1111-1111-1111"));
        assertFalse(redacted.toString().contains("supersecret"));
        assertFalse(redacted.toString().contains("user@example.com"));
        assertEquals(100.00, redacted.get("amount"));

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(redacted)
                .executionDurationMillis(0)
                .build());

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdAndRequestId(organization.getId(), requestId);

        assertEquals(1, records.size());
        AuditLogEntity entity = records.get(0);
        assertFalse(entity.getRedactedParameters().toString().contains("supersecret"));
        assertFalse(entity.getRedactedParameters().toString().contains("4111-1111-1111-1111"));
        assertEquals(100.00, entity.getRedactedParameters().get("amount"));
    }

    @Test
    @DisplayName("REG-AUDIT-7: Idempotency behavior is unchanged - requestId used for correlation, not uniqueness")
    void testIdempotencyUnchanged() {
        String requestId = UUID.randomUUID().toString();

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(0)
                .build());

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .redactedExecutionResult(Map.of("result", "success"))
                .executionDurationMillis(150)
                .build());

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdAndRequestId(organization.getId(), requestId);
        assertEquals(2, records.size());
    }

    @Test
    @DisplayName("REG-AUDIT-8: Failed executions produce appropriate audit events")
    void testFailedExecutionAuditEvents() {
        String requestId = UUID.randomUUID().toString();

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_STARTED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(0)
                .build());

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_FAILED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .redactedExecutionResult(Map.of("error", "timeout"))
                .executionDurationMillis(30000)
                .build());

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdAndRequestId(organization.getId(), requestId);

        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(r -> "EXECUTION_STARTED".equals(r.getEventType())));
        assertTrue(records.stream().anyMatch(r -> "EXECUTION_FAILED".equals(r.getEventType())));
    }

    @Test
    @DisplayName("REG-AUDIT-9: Same eventType for same requestId is rejected (constraint enforced)")
    void testDuplicateEventTypeRejected() {
        String requestId = UUID.randomUUID().toString();

        auditService.record(AuditRecord.builder()
                .organizationId(organization.getId().toString())
                .requestId(requestId)
                .eventType("EXECUTION_SUCCEEDED")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .decision(DecisionType.ALLOW)
                .redactedParameters(Map.of())
                .executionDurationMillis(100)
                .build());

        entityManager.flush();

        boolean threwConstraintViolation = false;
        try {
            auditService.record(AuditRecord.builder()
                    .organizationId(organization.getId().toString())
                    .requestId(requestId)
                    .eventType("EXECUTION_SUCCEEDED")
                    .actionType("PAYMENT:REFUND")
                    .targetResource("PAYMENT_API")
                    .decision(DecisionType.ALLOW)
                    .redactedParameters(Map.of())
                    .executionDurationMillis(200)
                    .build());
            entityManager.flush();
        } catch (Exception e) {
            threwConstraintViolation = true;
        }

        assertTrue(threwConstraintViolation, "Duplicate eventType for same requestId should be rejected");

        entityManager.clear();

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdAndRequestId(organization.getId(), requestId);
        assertEquals(1, records.size());
    }

    private AuditRecord toRecord(AuditLogEntity entity) {
        return AuditRecord.builder()
                .auditId(entity.getId().toString())
                .organizationId(entity.getOrganizationId().toString())
                .requestId(entity.getRequestId())
                .eventType(entity.getEventType())
                .agentId(entity.getAgentId() != null ? entity.getAgentId().toString() : null)
                .userId(entity.getUserId())
                .actionType(entity.getActionType())
                .targetResource(entity.getTargetResource())
                .decision(entity.getDecision() != null ?
                        DecisionType.valueOf(entity.getDecision()) : null)
                .redactedParameters(entity.getRedactedParameters())
                .redactedExecutionResult(entity.getRedactedExecutionResult())
                .executionDurationMillis(entity.getExecutionDurationMs())
                .prevRecordHash(entity.getPrevRecordHash())
                .currentRecordHash(entity.getCurrentRecordHash())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
