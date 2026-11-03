package io.netherite.connectors.aierp;

import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.routing.model.ResourceRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiErpResourceConnectorTest {

    private SsrfGuardValidator ssrfGuardValidator;
    private AiErpTokenService tokenService;
    private AiErpActionDefinitionRegistry registry;
    private RestTemplate restTemplate;
    private AiErpResourceConnector connector;

    @BeforeEach
    void setUp() {
        ssrfGuardValidator = Mockito.mock(SsrfGuardValidator.class);
        when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(true);

        tokenService = Mockito.mock(AiErpTokenService.class);
        when(tokenService.getValidToken(any(), any(), any(), any())).thenReturn("valid-jwt");

        registry = new AiErpActionDefinitionRegistry();
        connector = new AiErpResourceConnector(ssrfGuardValidator, tokenService, registry);

        restTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(connector, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("Connector type is AI_ERP")
    void testConnectorType() {
        assertThat(connector.getConnectorType()).isEqualTo(ConnectorType.AI_ERP);
    }

    @Test
    @DisplayName("Read action GET_SCHEMA executes GET with JWT bearer header")
    void testGetSchemaExecution() {
        String baseUrl = "https://erp.example.com";

        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"entities\":[{\"name\":\"account\"}]}", HttpStatus.OK));

        CanonicalAction action = CanonicalAction.builder()
                .actionId("exec-101")
                .organizationId("00000000-0000-0000-0000-000000000001")
                .agentId("agent-1")
                .targetResource("ai-erp-prod")
                .actionType("AI_ERP:GET_SCHEMA")
                .parameters(Map.of())
                .build();

        ResourceRoute route = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ai-erp-prod")
                .connectorType(ConnectorType.AI_ERP)
                .baseUrl(baseUrl)
                .sensitivityLevel(SensitivityLevel.LOW)
                .build();

        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-101")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("email", "admin@erp.local", "password", "secret"))
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));

        assertThat(uriCaptor.getValue().getPath()).isEqualTo("/api/v3.0/p/agent/schema");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer valid-jwt");
    }

    @Test
    @DisplayName("Path parameter interpolation: GET_SCHEMA_ENTITY interpolates {entity}")
    void testPathParameterInterpolation() {
        String baseUrl = "https://erp.example.com";

        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"entity\":\"sales_order\"}", HttpStatus.OK));

        CanonicalAction action = CanonicalAction.builder()
                .actionId("exec-102")
                .organizationId("00000000-0000-0000-0000-000000000001")
                .agentId("agent-1")
                .targetResource("ai-erp-prod")
                .actionType("AI_ERP:GET_SCHEMA_ENTITY")
                .parameters(Map.of("entity", "sales_order"))
                .build();

        ResourceRoute route = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ai-erp-prod")
                .connectorType(ConnectorType.AI_ERP)
                .baseUrl(baseUrl)
                .pathTemplate("/api/v3.0/p/agent/schema/{entity}")
                .httpMethod("GET")
                .sensitivityLevel(SensitivityLevel.LOW)
                .build();

        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-102")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("email", "admin@erp.local", "password", "secret"))
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        assertThat(result.isSuccessful()).isTrue();
        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(uriCaptor.getValue().getPath()).isEqualTo("/api/v3.0/p/agent/schema/sales_order");
    }

    @Test
    @DisplayName("Application-level failure under HTTP 200 is treated as unsuccessful")
    void testApplicationFailureUnderHttp200() {
        String baseUrl = "https://erp.example.com";

        // Application-level error returned with HTTP 200
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":false,\"message\":\"Insufficient stock for product SKU-99\"}", HttpStatus.OK));

        CanonicalAction action = CanonicalAction.builder()
                .actionId("exec-103")
                .organizationId("00000000-0000-0000-0000-000000000001")
                .agentId("agent-1")
                .targetResource("ai-erp-prod")
                .actionType("AI_ERP:PLACE_SALES_ORDER")
                .parameters(Map.of("customer", "Acme", "sku", "SKU-99"))
                .build();

        ResourceRoute route = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ai-erp-prod")
                .connectorType(ConnectorType.AI_ERP)
                .baseUrl(baseUrl)
                .sensitivityLevel(SensitivityLevel.HIGH)
                .build();

        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-103")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("email", "admin@erp.local", "password", "secret"))
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).contains("Insufficient stock for product SKU-99");
    }

    @Test
    @DisplayName("Transparent 401 recovery: refreshes token and retries once")
    void test401RecoveryAndRetry() {
        String baseUrl = "https://erp.example.com";

        when(tokenService.refreshTokenOrReacquire(any(), any(), any(), any())).thenReturn("refreshed-jwt");

        // First call throws 401 HttpClientErrorException, second call succeeds
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Unauthorized"))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"data\":\"ok\"}", HttpStatus.OK));

        CanonicalAction action = CanonicalAction.builder()
                .actionId("exec-104")
                .organizationId("00000000-0000-0000-0000-000000000001")
                .agentId("agent-1")
                .targetResource("ai-erp-prod")
                .actionType("AI_ERP:GET_SCHEMA")
                .parameters(Map.of())
                .build();

        ResourceRoute route = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ai-erp-prod")
                .connectorType(ConnectorType.AI_ERP)
                .baseUrl(baseUrl)
                .sensitivityLevel(SensitivityLevel.LOW)
                .build();

        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-104")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("email", "admin@erp.local", "password", "secret"))
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        assertThat(result.isSuccessful()).isTrue();
        verify(tokenService, times(1)).refreshTokenOrReacquire(any(), any(), any(), any());
        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("Protocol errors are reported as protocol errors, not timeouts")
    void testProtocolErrorIsNotReportedAsTimeout() {
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new ResourceAccessException(
                        "PATCH failed",
                        new ProtocolException("Invalid HTTP method: PATCH")));

        ConnectorExecutionResult result = connector.execute(updateRequest());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage())
                .contains("protocol error")
                .contains("Invalid HTTP method: PATCH")
                .doesNotContain("timed out");
    }

    @Test
    @DisplayName("Timeouts are reported as timeouts")
    void testTimeoutIsReportedAsTimeout() {
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new ResourceAccessException(
                        "read timeout",
                        new HttpTimeoutException("request timed out")));

        ConnectorExecutionResult result = connector.execute(updateRequest());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage())
                .contains("timed out")
                .contains("HttpTimeoutException")
                .doesNotContain("protocol error");
    }

    @Test
    @DisplayName("Connection failures are reported as unreachable, with the root cause")
    void testConnectionFailureIsReportedAsUnreachable() {
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new ResourceAccessException(
                        "connect failed",
                        new java.net.ConnectException("Connection refused")));

        ConnectorExecutionResult result = connector.execute(updateRequest());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage())
                .contains("unreachable")
                .contains("ConnectException")
                .contains("Connection refused")
                .doesNotContain("timed out");
    }

    private ConnectorExecutionRequest updateRequest() {
        String customerId = "10727423-9f10-b258-ae5b-cadc0d302f65";

        CanonicalAction action = CanonicalAction.builder()
                .actionId("exec-update")
                .organizationId("00000000-0000-0000-0000-000000000001")
                .agentId("agent-1")
                .targetResource("ai-erp-prod")
                .actionType("AI_ERP:UPDATE_RECORD")
                .parameters(Map.of(
                        "entity", "customer",
                        "id", customerId,
                        "credit_limit", 9500))
                .build();

        ResourceRoute route = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ai-erp-prod")
                .connectorType(ConnectorType.AI_ERP)
                .baseUrl("https://erp.example.com")
                .pathTemplate("/api/v3.0/p/agent/records/{entity}/{id}")
                .httpMethod("PATCH")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .build();

        return ConnectorExecutionRequest.builder()
                .executionId("exec-update")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("email", "admin@erp.local", "password", "secret"))
                .build();
    }
}
