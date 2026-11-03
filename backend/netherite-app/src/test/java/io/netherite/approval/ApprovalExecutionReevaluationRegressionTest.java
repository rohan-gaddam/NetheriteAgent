package io.netherite.approval;

import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.service.ApprovalExecutionRunner;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.common.context.TenantContext;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance contract: an APPROVED ticket's continuation must actually execute the action a human
 * approved. Today it never does, for two independent reasons found while writing this suite:
 *
 * <p><b>BLOCK-1 (currently observed):</b> {@code JpaApprovalService.createTicket} serialises the
 * {@link CanonicalAction} into the ticket with a bare {@code new ObjectMapper()} that has no JSR-310
 * module. Serialising {@code Instant timestamp} throws
 * {@code InvalidDefinitionException("Java 8 date/time type java.time.Instant not supported")}, the
 * code silently swallows it with {@code Map.of()}, and {@code canonicalAction} is persisted
 * <b>empty</b>. The runner then deserialises an empty action and
 * {@code DefaultResourceRoutingService.resolveRoute} returns empty via its silent
 * {@code organizationId == null} guard, so the ticket fails with "Target resource is no longer
 * available" before reaching policy or the connector.</p>
 *
 * <p><b>BLOCK-2 (APR-1, masked behind BLOCK-1):</b> once the action round-trips, the continuation
 * still calls {@link ConnectorExecutionService#execute(CanonicalAction)}, which re-evaluates policy
 * from unchanged facts. The same {@code HUMAN_APPROVAL} decision is produced again, a second
 * approval ticket is created, and the continuation deterministically fails.</p>
 *
 * <p>The suite intentionally does <b>not</b> use {@code @Transactional}: the continuation must
 * observe already-committed ticket state, exactly as the async scheduler does in production.</p>
 *
 * <p>Regression expectation (green only after both blocks are fixed): the approved action reaches
 * the connector and the ticket records a successful {@code executionResult} with no
 * {@code executionError}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ApprovalExecutionReevaluationRegressionTest.CountingConnectorConfig.class)
class ApprovalExecutionReevaluationRegressionTest {

    static final AtomicInteger CONNECTOR_INVOCATIONS = new AtomicInteger();

    /**
     * Replaces the real HTTP connector by bean name so the continuation is observable and never
     * opens a socket (mirrors DenialSideEffectSecurityTest).
     */
    static class CountingConnectorConfig {
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
                    CONNECTOR_INVOCATIONS.incrementAndGet();
                    return ConnectorExecutionResult.builder()
                            .successful(true)
                            .statusCode(200)
                            .responseBody(Map.of("result", "approved-action-executed"))
                            .executionDurationMillis(1)
                            .build();
                }
            };
        }
    }

    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentService;
    @Autowired private ResourceDomainService resourceDomainService;
    @Autowired private BindingDomainService bindingDomainService;
    @Autowired private ConnectorExecutionService connectorExecutionService;
    @Autowired private ApprovalService approvalService;
    @Autowired private ApprovalExecutionRunner approvalExecutionRunner;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;

    @BeforeEach
    void setUp() {
        CONNECTOR_INVOCATIONS.set(0);
        organization = organizationService.createOrganization(
                "Approval Reeval Corp", "apr1-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(
                organization.getId(), "ApprovalReevalAgent", "APR-1 regression agent", "TIER_3");
        resource = resourceDomainService.createResource(
                organization.getId(), "PAYMENT_API", ConnectorType.HTTP_REST,
                // Literal public IP (example.com's): the strict SSRF guard is fail-closed on DNS
                // failures, so a non-resolvable hostname would be rejected before the connector.
                "https://93.184.216.34", null, SensitivityLevel.MEDIUM, "Payment API");
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
    @DisplayName("APR-1. An approved ticket's continuation executes the action instead of re-requesting approval")
    void approvedContinuationExecutesTheAction() {
        CanonicalAction action = buildAction();

        // Step 1: a TIER_3 action is gated behind HUMAN_APPROVAL and creates a PENDING ticket.
        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> connectorExecutionService.execute(action));
        String details = thrown.getDetails() != null ? thrown.getDetails() : thrown.getMessage();
        assertNotNull(details);
        assertTrue(details.contains("Ticket ID:"), "expected an approval ticket, got: " + details);
        String ticketId = details.substring(details.indexOf("Ticket ID:") + "Ticket ID:".length()).trim();

        ApprovalRequestEntity pending = approvalRequestRepository.findById(UUID.fromString(ticketId)).orElse(null);
        assertNotNull(pending, "the approval ticket must be persisted");
        assertEquals("PENDING", pending.getStatus());

        // Step 2: the designated person approves it.
        approvalService.resolveTicket(ticketId, ApprovalStatus.APPROVED,
                UUID.randomUUID().toString(), "Approved - APR-1 regression");
        ApprovalRequestEntity approved = approvalRequestRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertEquals("APPROVED", approved.getStatus());

        // Step 3: claim the continuation lease (same query ApprovalContinuationService uses), then
        // run the real runner synchronously so the assertion is deterministic.
        UUID ticketUuid = UUID.fromString(ticketId);
        String nodeId = "node-1";
        Instant now = Instant.now();
        int claimed = approvalRequestRepository.claimExecution(
                ticketUuid, nodeId, now, now.plus(Duration.ofMinutes(10)));
        assertEquals(1, claimed, "test setup must acquire the continuation claim");

        ApprovalExecutionRunner rawRunner = AopTestUtils.getTargetObject(approvalExecutionRunner);
        rawRunner.execute(ticketId, nodeId);

        // Step 4: the approved action must actually execute.
        ApprovalRequestEntity executed = approvalRequestRepository.findById(ticketUuid).orElseThrow();

        List<String> problems = new ArrayList<>();
        if (CONNECTOR_INVOCATIONS.get() == 0) {
            problems.add("the approved continuation never reached a connector "
                    + "(the action a human approved was not executed)");
        }
        if (executed.getExecutionResult() == null) {
            problems.add("executionResult was null - the continuation recorded no successful outcome");
        }
        if (executed.getExecutionError() != null) {
            problems.add("executionError was set to: \"" + executed.getExecutionError()
                    + "\" (the continuation failed deterministically instead of executing)");
        }
        int tickets = approvalRequestRepository.findAllByOrganizationId(organization.getId()).size();
        if (tickets > 1) {
            problems.add("the continuation created a duplicate approval ticket (total tickets=" + tickets
                    + ") instead of honouring the existing approval");
        }

        assertTrue(problems.isEmpty(),
                "APR-1 regression - an APPROVED ticket must execute its action:\n  - "
                        + String.join("\n  - ", problems));
    }

    private CanonicalAction buildAction() {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(registeredAgent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                // Resource NAME (resolveRoute looks the resource up by name within the tenant).
                .targetResource(resource.getName())
                .parameters(Map.of("amount", 100, "currency", "USD"))
                .idempotencyKey("apr1-" + UUID.randomUUID())
                .timestamp(Instant.now())
                .build();
    }
}
