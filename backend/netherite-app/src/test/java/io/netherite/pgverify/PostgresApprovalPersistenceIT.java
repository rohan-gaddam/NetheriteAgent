package io.netherite.pgverify;

import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.entity.UserEntity;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WORKSTREAM 1 - Phases 3/5/6/7 verification against a REAL PostgreSQL 16 instance.
 *
 * Boots the full application context with Flyway V1..V14 enabled and
 * {@code ddl-auto: validate} active. Any mismatch between the entity model and the
 * migrated schema fails the context at startup, so getting this far already proves:
 *   - the approval_requests schema now satisfies the ApprovalRequestEntity contract,
 *   - JSON columns round-trip through jsonb,
 *   - @Version optimistic locking works against a real PostgreSQL dialect.
 */
@SpringBootTest
@ActiveProfiles("pgverify")
class PostgresApprovalPersistenceIT {

    @Autowired private ApprovalService approvalService;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private io.netherite.domain.repository.UserRepository userRepository;
@Autowired private io.netherite.domain.repository.OrganizationRepository organizationRepository;
    @Autowired private DataSource dataSource;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;
    private UserEntity approver;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization(
                "PGVerify Corp", "pgv-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "PGVerifyAgent", "Postgres Verification Agent", "TIER_1");
        resource = resourceDomainService.createResource(
                organization.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                "https://api.example.com", null,
                io.netherite.common.model.SensitivityLevel.MEDIUM, "Payment API");
        bindingDomainService.bindAgentToResource(
                organization.getId(), registeredAgent.agent().getId(), resource.getId(),
                List.of("PAYMENT:REFUND", "*"));

        approver = userRepository.save(UserEntity.builder()
                .organizationId(organization.getId())
                .email("pgverify-approver-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("PG Verify Approver")
                .status("ACTIVE")
                .build());

        // WS6: designate the approver as the organization's single authorized
        // person, which is what authorizes approval decisions.
        organization.setAuthorizedPersonUserId(approver.getId());
        organizationRepository.save(organization);
        organizationRepository.flush();

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

    private DecisionContext buildContext() {
        CanonicalAction action = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource(resource.getId().toString())
                .parameters(Map.of("amount", 100, "currency", "USD"))
                .idempotencyKey("pgverify-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .build();

        return DecisionContext.builder()
                .contextId(UUID.randomUUID().toString())
                .action(action)
                .businessFacts(Map.of("risk", "low"))
                .stateFacts(Map.of("agentTrustTier", "TIER_1"))
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Flyway migrated exactly V1..V14 with stable non-zero checksums on fresh schema")
    void flywayAppliedAllMigrations() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertEquals(14, count, "Expected exactly 14 successful Flyway migrations on a fresh schema");

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14"), versions);

        jdbc.query("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank",
                (java.sql.ResultSet rs2) -> {
                    int rows = 0;
                    while (rs2.next()) {
                        rows++;
                        assertNotNull(rs2.getString("version"));
                        assertNotEquals(0L, rs2.getLong("checksum"),
                                "Migration V" + rs2.getString("version") + " must have a non-zero checksum");
                    }
                    return rows == 14;
                });
    }

    @Test
    @DisplayName("approval_requests columns/types exactly satisfy the entity contract")
    void approvalRequestColumnsMatchEntityContract() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            Map<String, String> expected = Map.ofEntries(
                    Map.entry("id", "uuid"),
                    Map.entry("organization_id", "uuid"),
                    Map.entry("agent_id", "uuid"),
                    Map.entry("user_id", "varchar"),
                    Map.entry("action_type", "varchar"),
                    Map.entry("target_resource", "varchar"),
                    Map.entry("canonical_action", "jsonb"),
                    Map.entry("decision_context", "jsonb"),
                    Map.entry("triggered_policies", "jsonb"),
                    Map.entry("status", "varchar"),
                    Map.entry("approver_decision", "varchar"),
                    Map.entry("approver_notes", "text"),
                    Map.entry("approved_by_user_id", "uuid"),
                    Map.entry("approver_user_identifier", "varchar"),
                    Map.entry("execution_result", "text"),
                    Map.entry("execution_error", "text"),
                    Map.entry("expires_at", "timestamptz"),
                    Map.entry("resolved_at", "timestamptz"),
                    Map.entry("created_at", "timestamptz"),
                    Map.entry("version", "int8"));

            Map<String, String> actual = new java.util.HashMap<>();
            java.util.Set<String> nullability = new java.util.HashSet<>();
            try (var rs = meta.getColumns(null, "public", "approval_requests", "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    actual.put(name, type != null ? type.toLowerCase() : "");
                    if (!nullable) {
                        nullability.add(name);
                    }
                }
            }

            for (Map.Entry<String, String> e : expected.entrySet()) {
                assertTrue(actual.containsKey(e.getKey()), "Missing column " + e.getKey());
                assertEquals(e.getValue(), actual.get(e.getKey()),
                        "Wrong type for column " + e.getKey());
            }

            assertTrue(nullability.contains("organization_id"));
            assertTrue(nullability.contains("agent_id"));
            assertTrue(nullability.contains("canonical_action"));
            assertTrue(nullability.contains("decision_context"));
            assertTrue(nullability.contains("triggered_policies"));
            assertTrue(nullability.contains("expires_at"));
            assertTrue(nullability.contains("version"),
                    "version must be NOT NULL: " + nullability);
        }
    }

    @Test
    @DisplayName("JSONB columns round-trip structured JSON content at the repository level")
    void jsonbColumnsRoundTripAtRepositoryLevel() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("actionId", UUID.randomUUID().toString());
        action.put("actionType", "PAYMENT:REFUND");
        action.put("amount", 100);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("riskScore", 42);
        context.put("verified", true);
        context.put("tags", List.of("a", "b"));

        ApprovalRequestEntity entity = ApprovalRequestEntity.builder()
                .organizationId(organization.getId())
                .agentId(registeredAgent.agent().getId())
                .userId("u-" + UUID.randomUUID())
                .actionType("PAYMENT:REFUND")
                .targetResource(resource.getId().toString())
                .canonicalAction(action)
                .decisionContext(context)
                .triggeredPolicies(List.of("base-policy", "llm-policy"))
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .build();

        entity = approvalRequestRepository.saveAndFlush(entity);

        ApprovalRequestEntity loaded = approvalRequestRepository
                .findByIdAndOrganizationId(entity.getId(), organization.getId())
                .orElseThrow();

        assertNotNull(loaded.getCanonicalAction());
        assertEquals("PAYMENT:REFUND", loaded.getCanonicalAction().get("actionType"));
        assertEquals(100, ((Number) loaded.getCanonicalAction().get("amount")).intValue());
        assertEquals(42, ((Number) loaded.getDecisionContext().get("riskScore")).intValue());
        assertEquals(Boolean.TRUE, loaded.getDecisionContext().get("verified"));
        assertEquals(List.of("a", "b"), loaded.getDecisionContext().get("tags"));
        assertEquals(List.of("base-policy", "llm-policy"), loaded.getTriggeredPolicies());
    }

    @Test
    @DisplayName("Approval creation, execution_result/error columns, and @Version increment work via the service")
    void approvalPersistResultAndVersionIncrement() {
        var ticket = approvalService.createTicket(buildContext(), List.of("base-policy"));
        assertNotNull(ticket.getTicketId());

        ApprovalRequestEntity stored = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        assertEquals(0L, stored.getVersion());
        assertEquals(List.of("base-policy"), stored.getTriggeredPolicies());

        var resolved = approvalService.resolveTicketWithResult(
                ticket.getTicketId(), ApprovalStatus.APPROVED, approver.getId().toString(),
                "Approved by PG verification", "{\"status\":\"EXECUTED\",\"refundId\":\"r-123\"}", null);

        assertEquals(ApprovalStatus.APPROVED, resolved.getStatus());
        assertEquals("{\"status\":\"EXECUTED\",\"refundId\":\"r-123\"}", resolved.getExecutionResult());
        assertNotNull(resolved.getResolvedAt());

        ApprovalRequestEntity afterResolve = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        assertEquals("APPROVED", afterResolve.getStatus());
        assertEquals("{\"status\":\"EXECUTED\",\"refundId\":\"r-123\"}", afterResolve.getExecutionResult());
        assertEquals(approver.getId().toString(), afterResolve.getApproverUserIdentifier());
        assertEquals(1L, afterResolve.getVersion(), "@Version must increment on update");
    }

    @Test
    @DisplayName("Optimistic locking: stale concurrent update is rejected on real PostgreSQL")
    void optimisticLockingRejectsStaleUpdate() {
        var ticket = approvalService.createTicket(buildContext(), List.of("base-policy"));

        ApprovalRequestEntity firstLoad = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        ApprovalRequestEntity secondLoad = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();

        firstLoad.setStatus("APPROVED");
        approvalRequestRepository.saveAndFlush(firstLoad);

        secondLoad.setStatus("REJECTED");
        assertInstanceOf(ObjectOptimisticLockingFailureException.class,
                assertThrows(Exception.class, () -> approvalRequestRepository.saveAndFlush(secondLoad)));
    }

    @Test
    @DisplayName("Execution error persists when resolution carries an error")
    void executionErrorPersists() {
        var ticket = approvalService.createTicket(buildContext(), List.of("base-policy"));
        var resolved = approvalService.resolveTicketWithResult(
                ticket.getTicketId(), ApprovalStatus.REJECTED, approver.getId().toString(),
                "Rejected: upstream failure", null, "PAYMENT_GATEWAY_TIMEOUT");

        assertNotNull(resolved.getExecutionError());

        ApprovalRequestEntity stored = approvalRequestRepository
                .findByIdAndOrganizationId(UUID.fromString(ticket.getTicketId()), organization.getId())
                .orElseThrow();
        assertEquals("PAYMENT_GATEWAY_TIMEOUT", stored.getExecutionError());
        assertEquals("REJECTED", stored.getStatus());
    }
}