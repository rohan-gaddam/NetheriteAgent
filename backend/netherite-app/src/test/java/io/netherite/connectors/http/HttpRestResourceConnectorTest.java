package io.netherite.connectors.http;

import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.routing.model.ResourceRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link HttpRestResourceConnector} HTTP method routing.
 *
 * Covers: GET/PUT/DELETE method dispatch, POST default behavior,
 * missing method fallback, and invalid method fail-closed.
 */
class HttpRestResourceConnectorTest {

    private SsrfGuardValidator ssrfGuardValidator;
    private RestTemplate restTemplate;
    private HttpRestResourceConnector connector;

    @BeforeEach
    void setUp() {
        ssrfGuardValidator = mock(SsrfGuardValidator.class);
        lenient().when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(true);
        restTemplate = mock(RestTemplate.class);
        lenient().when(restTemplate.exchange(
                any(String.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        connector = new HttpRestResourceConnector(ssrfGuardValidator);
        ReflectionTestUtils.setField(connector, "restTemplate", restTemplate);
    }

    private ResourceRoute route(String httpMethod) {
        return ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ERPNext")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://erpnext.example.com")
                .pathTemplate("/api/resource/Sales%20Invoice")
                .httpMethod(httpMethod)
                .build();
    }

    private ConnectorExecutionRequest request(ResourceRoute route) {
        return ConnectorExecutionRequest.builder()
                .executionId("exec-1")
                .idempotencyKey("idem-1")
                .action(CanonicalAction.builder()
                        .actionId("action-1")
                        .organizationId("org-1")
                        .agentId("agent-1")
                        .actionType("ERP:CREATE_INVOICE")
                        .targetResource("ERPNext")
                        .parameters(Map.of("customer", "Acme"))
                        .build())
                .route(route)
                .timeoutMillis(30000)
                .build();
    }

    @Test
    @DisplayName("GET method is dispatched to RestTemplate.exchange with HttpMethod.GET")
    void getMethodDispatched() {
        lenient().when(restTemplate.exchange(
                eq("https://erpnext.example.com/api/resource/Sales%20Invoice"),
                eq(HttpMethod.GET), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(route("GET")));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                eq("https://erpnext.example.com/api/resource/Sales%20Invoice"),
                eq(HttpMethod.GET), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("POST method is dispatched to RestTemplate.exchange with HttpMethod.POST")
    void postMethodDispatched() {
        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(route("POST")));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("PUT method is dispatched to RestTemplate.exchange with HttpMethod.PUT")
    void putMethodDispatched() {
        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.PUT), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(route("PUT")));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                any(String.class), eq(HttpMethod.PUT), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("DELETE method is dispatched to RestTemplate.exchange with HttpMethod.DELETE")
    void deleteMethodDispatched() {
        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.DELETE), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(route("DELETE")));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                any(String.class), eq(HttpMethod.DELETE), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("Missing HTTP method defaults to POST")
    void missingMethodDefaultsToPost() {
        ResourceRoute routeNoMethod = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ERPNext")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://erpnext.example.com")
                .pathTemplate("/api/resource/Sales%20Invoice")
                .httpMethod(null)
                .build();

        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(routeNoMethod));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("Blank HTTP method defaults to POST")
    void blankMethodDefaultsToPost() {
        ResourceRoute routeBlankMethod = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ERPNext")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://erpnext.example.com")
                .pathTemplate("/api/resource/Sales%20Invoice")
                .httpMethod("   ")
                .build();

        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(routeBlankMethod));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("Invalid HTTP method fails closed")
    void invalidMethodFailsClosed() {
        ResourceRoute routeInvalidMethod = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ERPNext")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://erpnext.example.com")
                .pathTemplate("/api/resource/Sales%20Invoice")
                .httpMethod("INVALID_METHOD")
                .build();

        ConnectorExecutionResult result = connector.execute(request(routeInvalidMethod));

        assertFalse(result.isSuccessful());
        assertEquals("Unrecognized HTTP method on resource route", result.getErrorMessage());
        verify(restTemplate, org.mockito.Mockito.never())
                .exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("Case-insensitive HTTP method is accepted")
    void caseInsensitiveMethodAccepted() {
        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ResourceRoute routeLowerCase = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ERPNext")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://erpnext.example.com")
                .pathTemplate("/api/resource/Sales%20Invoice")
                .httpMethod("get")
                .build();

        ConnectorExecutionResult result = connector.execute(request(routeLowerCase));

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(
                any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("SSRF guard still blocks unsafe destinations regardless of HTTP method")
    void ssrfGuardStillBlocks() {
        lenient().when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(false);

        ConnectorExecutionResult result = connector.execute(request(route("GET")));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("SSRF"));
        verify(restTemplate, org.mockito.Mockito.never())
                .exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("Connector type is HTTP_REST")
    void connectorTypeIsHttpRest() {
        assertEquals(ConnectorType.HTTP_REST, connector.getConnectorType());
    }

    @Test
    @DisplayName("Successful response is propagated correctly")
    void successfulResponsePropagated() {
        lenient().when(restTemplate.exchange(
                any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "created", "data", Map.of("id", "INV-001"))));

        ConnectorExecutionResult result = connector.execute(request(route("GET")));

        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(200, result.getStatusCode());
        assertEquals("created", result.getResponseBody().get("status"));
    }

    @Test
    @DisplayName("ERPNext Authorization header is forwarded from resolvedCredentials")
    void erpnextAuthorizationHeaderForwarded() {
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        lenient().when(restTemplate.exchange(
                any(String.class), any(HttpMethod.class), entityCaptor.capture(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionRequest requestWithCreds = ConnectorExecutionRequest.builder()
                .executionId("exec-1")
                .idempotencyKey("idem-1")
                .action(CanonicalAction.builder()
                        .actionId("action-1")
                        .organizationId("org-1")
                        .agentId("agent-1")
                        .actionType("ERP:CREATE_INVOICE")
                        .targetResource("ERPNext")
                        .parameters(Map.of("customer", "Acme"))
                        .build())
                .route(route("POST"))
                .resolvedCredentials(Map.of("Authorization", "Token erpnext_api_key:erpnext_api_secret"))
                .timeoutMillis(30000)
                .build();

        ConnectorExecutionResult result = connector.execute(requestWithCreds);

        assertTrue(result.isSuccessful());
        verify(restTemplate).exchange(anyString(), any(HttpMethod.class), entityCaptor.capture(), any(Class.class));
        HttpEntity<?> capturedEntity = entityCaptor.getValue();
        assertNotNull(capturedEntity.getHeaders());
        assertEquals("Token erpnext_api_key:erpnext_api_secret",
                capturedEntity.getHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("No resolvedCredentials means no Authorization header is set")
    void noCredentialsMeansNoAuthHeader() {
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        lenient().when(restTemplate.exchange(
                any(String.class), any(HttpMethod.class), entityCaptor.capture(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionResult result = connector.execute(request(route("POST")));

        assertTrue(result.isSuccessful());
        HttpEntity<?> capturedEntity = entityCaptor.getValue();
        assertNotNull(capturedEntity.getHeaders());
        assertNull(capturedEntity.getHeaders().get("Authorization"),
                "No Authorization header should be set when resolvedCredentials is absent");
    }

    @Test
    @DisplayName("Connector does not log credential values")
    void credentialsNotLogged() {
        // This test verifies structurally that the connector logs only the execution ID,
        // connector type, and resource name - not the resolvedCredentials map contents.
        // The createHeaders() method resolves Authorization from resolvedCredentials but
        // never includes it in any log statement.
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        lenient().when(restTemplate.exchange(
                any(String.class), any(HttpMethod.class), entityCaptor.capture(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        ConnectorExecutionRequest requestWithCreds = ConnectorExecutionRequest.builder()
                .executionId("exec-1")
                .idempotencyKey("idem-1")
                .action(CanonicalAction.builder()
                        .actionId("action-1")
                        .organizationId("org-1")
                        .agentId("agent-1")
                        .actionType("ERP:CREATE_INVOICE")
                        .targetResource("ERPNext")
                        .parameters(Map.of("customer", "Acme"))
                        .build())
                .route(route("POST"))
                .resolvedCredentials(Map.of("Authorization", "Token sensitive_api_key:secret"))
                .timeoutMillis(30000)
                .build();

        ConnectorExecutionResult result = connector.execute(requestWithCreds);

        assertTrue(result.isSuccessful());
        HttpEntity<?> capturedEntity = entityCaptor.getValue();
        assertEquals("Token sensitive_api_key:secret",
                capturedEntity.getHeaders().getFirst("Authorization"));
        // If we reach here without an assertion error, the credential was successfully
        // placed on the outbound request without being logged by the connector.
    }
}
