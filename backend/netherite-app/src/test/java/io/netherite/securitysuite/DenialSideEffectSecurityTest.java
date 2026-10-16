package io.netherite.securitysuite;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.domain.entity.AuditLogEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch H — dedicated security suite.
 *
 * <p>Denied actions must have zero side effects: a DENY decision never reaches a connector
 * and always writes an accurate, redacted audit event — the properties that make an
 * authorization control auditable rather than just observable.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(DenialSideEffectSecurityTest.CountingConnectorConfig.class)
class DenialSideEffectSecurityTest {

    static final AtomicInteger CONNECTOR_INVOCATIONS = new AtomicInteger();

    @java.lang.SuppressWarnings("checkstyle:ClassDataAbstractionCoupling")
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
                            .responseBody(Map.of("result", "should-never-happen-on-deny"))
                            .executionDurationMillis(1)
                            .build();
                }
            };
        }
    }

    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentDomainService;
    @Autowired private ConnectorExecutionService connectorExecutionService;
    @Autowired private AuditLogRepository auditLogRepository;

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult agent;

    @BeforeEach
    void setUp() {
        CONNECTOR_INVOCATIONS.set(0);
        organization = organizationService.createOrganization(
                "Denial Corp", "dny-" + UUID.randomUUID().toString().substring(0, 8));
        agent = agentDomainService.registerAgent(
                organization.getId(), "DenialAgent", "Denial side-effect agent", "TIER_1");
    }

    private CanonicalAction deniedAction(String secretValue) {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(organization.getId().toString())
                .agentId(agent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of("password", secretValue))
                .timestamp(Instant.now())
                .build();
    }

    @Test
    @DisplayName("DEN-1. A policy denial throws, never invokes a connector, and audits accurately")
    void denialHasNoSideEffectAndIsAudited() {
        String executionId = UUID.randomUUID().toString();
        CanonicalAction action = CanonicalAction.builder()
                .actionId(executionId)
                .organizationId(organization.getId().toString())
                .agentId(agent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of("password", "super-secret-value"))
                .timestamp(Instant.now())
                .build();

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> connectorExecutionService.execute(action));
        assertTrue(thrown.getMessage().contains("DENY"));

        assertEquals(0, CONNECTOR_INVOCATIONS.get(), "DENY decision must not reach a connector");

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        AuditLogEntity denial = records.stream()
                .filter(r -> executionId.equals(r.getRequestId()) && "DENY".equals(r.getEventType()))
                .findFirst()
                .orElse(null);
        assertNotNull(denial, "DENY decision must produce an audit event");
        assertEquals("DENY", denial.getDecision());
        assertNotNull(denial.getRedactedParameters());
        assertEquals("[REDACTED]", denial.getRedactedParameters().get("password"));
    }

    @Test
    @DisplayName("DEN-2. Audited parameters of a denied action never contain the raw secret text")
    void deniedActionAuditOmitsRawSecret() {
        String executionId = UUID.randomUUID().toString();
        CanonicalAction action = CanonicalAction.builder()
                .actionId(executionId)
                .organizationId(organization.getId().toString())
                .agentId(agent.agent().getId().toString())
                .actionType("PAYMENT:REFUND")
                .targetResource("")
                .parameters(Map.of("password", "raw-secret-value-42"))
                .timestamp(Instant.now())
                .build();

        assertThrows(NetheriteException.class, () -> connectorExecutionService.execute(action));

        List<AuditLogEntity> records = auditLogRepository
                .findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        AuditLogEntity denial = records.stream()
                .filter(r -> executionId.equals(r.getRequestId()) && "DENY".equals(r.getEventType()))
                .findFirst()
                .orElse(null);
        assertNotNull(denial);
        assertTrue(denial.getRedactedParameters().values().stream()
                .noneMatch(v -> String.valueOf(v).contains("raw-secret-value-42")));
    }
}