package io.netherite.connectors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.service.ApprovalEventPublisher;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.DecisionType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.dashboard.service.HealthCheckService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.security.context.TenantContextHolder;
import io.netherite.security.key.CredentialEncryptionService;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.model.DistributedLock;
import io.netherite.state.spi.DistributedLockManager;
import io.netherite.state.spi.IdempotencyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5G: decryption happens only in the execution orchestration path, after the
 * agent-resource binding is authorized, and before the connector is invoked.
 */
@ExtendWith(MockitoExtension.class)
class ConnectorExecutionCredentialResolutionTest {

    private static final String VALID_KEY_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String AUTHORIZATION = "Token test-key:test-secret";

    @Mock private PolicyEvaluationService policyEvaluationService;
    @Mock private ResourceRoutingService resourceRoutingService;
    @Mock private SsrfGuardValidator ssrfGuardValidator;
    @Mock private IdempotencyStore idempotencyStore;
    @Mock private DistributedLockManager lockManager;
    @Mock private ApprovalService approvalService;
    @Mock private ApprovalEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private DataRedactionService dataRedactionService;
    @Mock private HealthCheckService healthCheckService;
    @Mock private NetheriteDistributedProperties distributedProperties;
    @Mock private NetheriteDistributedProperties.Lock lockProperties;

    private final ResourceConnector connector = mock(ResourceConnector.class);
    private final CredentialEncryptionService encryptionService =
            new CredentialEncryptionService(VALID_KEY_BASE64);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ConnectorExecutionService service;
    private UUID orgId;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        service = new ConnectorExecutionService(
                policyEvaluationService, resourceRoutingService, ssrfGuardValidator,
                List.of(connector), idempotencyStore, lockManager, approvalService,
                eventPublisher, auditService, dataRedactionService, healthCheckService,
                distributedProperties);

        orgId = UUID.randomUUID();
        agentId = UUID.randomUUID();

        lenient().when(distributedProperties.getLock()).thenReturn(lockProperties);
        lenient().when(lockProperties.getExecutionWaitMillis()).thenReturn(1_000L);
        lenient().when(lockProperties.getExecutionLeaseMillis()).thenReturn(1_000L);
        lenient().when(lockManager.tryAcquireLock(anyString(), any(), any()))
                .thenReturn(Optional.of(mock(DistributedLock.class)));
        lenient().when(ssrfGuardValidator.isUriPermitted(any())).thenReturn(true);
        lenient().when(connector.getConnectorType()).thenReturn(ConnectorType.ERPNEXT);
        lenient().when(connector.execute(any(ConnectorExecutionRequest.class)))
                .thenReturn(ConnectorExecutionResult.builder().successful(true).statusCode(200).build());
        lenient().when(dataRedactionService.redactParameters(anyString(), any())).thenReturn(Map.of());
        lenient().when(dataRedactionService.redactResult(anyString(), any())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Encrypted resource credentials are decrypted and forwarded to the connector")
    void decryptsCredentialsBeforeConnectorExecution() throws Exception {
        ReflectionTestUtils.setField(service, "credentialEncryptionService", encryptionService);
        stubAllowAndAuthorizedRoute(encryptedCredentials(), true);

        service.execute(action());

        ArgumentCaptor<ConnectorExecutionRequest> captor =
                ArgumentCaptor.forClass(ConnectorExecutionRequest.class);
        verify(connector).execute(captor.capture());
        assertEquals(AUTHORIZATION, captor.getValue().getResolvedCredentials().get("Authorization"));
    }

    @Test
    @DisplayName("A resource with encrypted credentials fails closed when no key is configured")
    void missingEncryptionKeyFailsClosed() throws Exception {
        stubAllowAndAuthorizedRoute(encryptedCredentials(), true);

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.execute(action()));

        assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, thrown.getErrorCode());
        verify(connector, never()).execute(any(ConnectorExecutionRequest.class));
    }

    @Test
    @DisplayName("Invalid ciphertext fails closed and the connector is not invoked")
    void invalidCiphertextFailsClosed() {
        ReflectionTestUtils.setField(service, "credentialEncryptionService", encryptionService);
        stubAllowAndAuthorizedRoute("not-valid-ciphertext", true);

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.execute(action()));

        assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, thrown.getErrorCode());
        verify(connector, never()).execute(any(ConnectorExecutionRequest.class));
    }

    @Test
    @DisplayName("An unauthorized binding is rejected before any credential decryption")
    void bindingIsCheckedBeforeDecryption() {
        ReflectionTestUtils.setField(service, "credentialEncryptionService", encryptionService);
        stubAllowAndAuthorizedRoute("not-valid-ciphertext", false);

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.execute(action()));

        assertEquals(SystemErrorCode.ERR_RESOURCE_NOT_BOUND, thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("not authorized"));
        verify(connector, never()).execute(any(ConnectorExecutionRequest.class));
    }

    private void stubAllowAndAuthorizedRoute(String credentialReference, boolean bindingAuthorized) {
        when(policyEvaluationService.evaluateDecision(any(CanonicalAction.class))).thenReturn(DecisionType.ALLOW);
        ResourceRoute route = ResourceRoute.builder()
                .resourceId(UUID.randomUUID().toString())
                .resourceName("ERPNEXT-TEST")
                .connectorType(ConnectorType.ERPNEXT)
                .baseUrl("https://erpnext.example.com")
                .httpMethod("POST")
                .credentialReference(credentialReference)
                .build();
        when(resourceRoutingService.resolveRoute(any(CanonicalAction.class))).thenReturn(Optional.of(route));
        when(resourceRoutingService.isBindingAuthorized(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bindingAuthorized);
    }

    private String encryptedCredentials() throws Exception {
        return encryptionService.encrypt(objectMapper.writeValueAsString(Map.of("Authorization", AUTHORIZATION)));
    }

    private CanonicalAction action() {
        return CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(orgId.toString())
                .agentId(agentId.toString())
                .actionType("CREATE QUOTATION")
                .targetResource("ERPNEXT-TEST")
                .parameters(Map.of("party", "Disposable Test Customer"))
                .timestamp(Instant.now())
                .build();
    }
}
