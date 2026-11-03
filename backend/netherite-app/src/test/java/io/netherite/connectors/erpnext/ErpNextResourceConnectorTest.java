package io.netherite.connectors.erpnext;

import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.routing.model.ResourceRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErpNextResourceConnectorTest {

    private SsrfGuardValidator ssrfGuardValidator;
    private RestTemplate restTemplate;
    private ErpNextResourceConnector connector;

    @BeforeEach
    void setUp() {
        ssrfGuardValidator = mock(SsrfGuardValidator.class);
        lenient().when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(true);
        restTemplate = mock(RestTemplate.class);
        lenient().when(restTemplate.exchange(
                        any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of("message", Map.of("name", "QTN-0001"))));

        connector = new ErpNextResourceConnector(ssrfGuardValidator);
        ReflectionTestUtils.setField(connector, "restTemplate", restTemplate);
    }

    private ResourceRoute route(String httpMethod) {
        return ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("quotation")
                .connectorType(ConnectorType.ERPNEXT)
                .baseUrl("https://erpnext.example.com")
                .httpMethod(httpMethod)
                .build();
    }

    private ResourceRoute route(String httpMethod, Map<String, Object> connectorConfig) {
        return ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("quotation")
                .connectorType(ConnectorType.ERPNEXT)
                .baseUrl("https://erpnext.example.com")
                .httpMethod(httpMethod)
                .connectorConfig(connectorConfig)
                .build();
    }

    private CanonicalAction action(String actionType, String targetResource, Map<String, Object> parameters) {
        return CanonicalAction.builder()
                .actionId("action-1")
                .organizationId("org-1")
                .agentId("agent-1")
                .actionType(actionType)
                .targetResource(targetResource)
                .parameters(parameters)
                .timestamp(Instant.now())
                .build();
    }

    private ConnectorExecutionRequest request(CanonicalAction action, ResourceRoute route) {
        return ConnectorExecutionRequest.builder()
                .executionId("exec-1")
                .idempotencyKey("idem-1")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("Authorization", "Token api_key:api_secret"))
                .timeoutMillis(30000)
                .build();
    }

    private ConnectorExecutionRequest createRequest(Map<String, Object> parameters) {
        return request(action("CREATE", "quotation", parameters), route(null));
    }

    private ArgumentCaptor<URI> captureUri() {
        return ArgumentCaptor.forClass(URI.class);
    }

    private ArgumentCaptor<HttpMethod> captureMethod() {
        return ArgumentCaptor.forClass(HttpMethod.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpEntity<?>> captureEntity() {
        return ArgumentCaptor.forClass(HttpEntity.class);
    }

    private void verifySingleExchange(ArgumentCaptor<URI> uriCaptor,
                                      ArgumentCaptor<HttpMethod> methodCaptor,
                                      ArgumentCaptor<HttpEntity<?>> entityCaptor) {
        verify(restTemplate).exchange(
                uriCaptor.capture(), methodCaptor.capture(), entityCaptor.capture(), any(Class.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(HttpEntity<?> entity) {
        return (Map<String, Object>) entity.getBody();
    }

    @Test
    @DisplayName("ERPNext connector is a Spring component and reports the ERPNEXT connector type")
    void registrationAndType() {
        assertTrue(ErpNextResourceConnector.class.isAnnotationPresent(Component.class));
        assertEquals(ConnectorType.ERPNEXT, connector.getConnectorType());
    }

    @Test
    @DisplayName("CREATE posts to the collection endpoint with credentials and correlation headers")
    void createPostsToCollectionEndpoint() {
        Map<String, Object> parameters = Map.of(
                "party", "Test Customer",
                "items", List.of(Map.of("item_code", "Test Item", "qty", 1, "rate", 100)));

        ConnectorExecutionResult result = connector.execute(createRequest(parameters));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(200, result.getStatusCode());
        assertEquals("QTN-0001", result.getResponseBody().get("name"));
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Quotation"), uriCaptor.getValue());
        assertEquals(HttpMethod.POST, methodCaptor.getValue());

        HttpEntity<?> entity = entityCaptor.getValue();
        assertEquals(parameters, bodyOf(entity));
        assertEquals("Token api_key:api_secret", entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals("exec-1", entity.getHeaders().getFirst("X-Netherite-Execution-Id"));
        assertEquals("idem-1", entity.getHeaders().getFirst("X-Netherite-Idempotency-Key"));
        assertNull(entity.getHeaders().getFirst("Cookie"));
    }

    @Test
    @DisplayName("UPDATE puts to the named-document endpoint and removes identity fields from the body")
    void updatePutsToNamedDocumentEndpoint() {
        CanonicalAction action = action("UPDATE", "sales_order",
                Map.of("name", "SAL-ORD-0001", "customer", "Test Customer"));
        ConnectorExecutionResult result = connector.execute(request(action, route(null)));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Sales%20Order/SAL-ORD-0001"),
                uriCaptor.getValue());
        assertEquals(HttpMethod.PUT, methodCaptor.getValue());

        Map<String, Object> body = bodyOf(entityCaptor.getValue());
        assertEquals("Test Customer", body.get("customer"));
        assertFalse(body.containsKey("name"));
        assertFalse(body.containsKey("docstatus"));
    }

    @Test
    @DisplayName("SUBMIT forces docstatus 1 even when the caller supplies another value")
    void submitForcesDocstatus() {
        CanonicalAction action = action("sales_order:SUBMIT", "erpnext",
                Map.of("name", "SAL-ORD-0001", "docstatus", 0, "customer", "Test Customer"));
        ConnectorExecutionResult result = connector.execute(request(action, route("PUT")));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Sales%20Order/SAL-ORD-0001"),
                uriCaptor.getValue());
        assertEquals(HttpMethod.PUT, methodCaptor.getValue());
        assertEquals(1, ((Number) bodyOf(entityCaptor.getValue()).get("docstatus")).intValue());
    }

    @Test
    @DisplayName("DELETE targets the named-document endpoint without a request body")
    void deleteTargetsNamedDocumentEndpoint() {
        CanonicalAction action = action("DELETE", "quotation",
                Map.of("name", "QTN-0001", "customer", "Ignored"));
        ConnectorExecutionResult result = connector.execute(request(action, route(null)));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Quotation/QTN-0001"),
                uriCaptor.getValue());
        assertEquals(HttpMethod.DELETE, methodCaptor.getValue());
        assertNull(entityCaptor.getValue().getBody());
    }

    @Test
    @DisplayName("Document type can be derived from an action-type prefix when the resource is generic")
    void documentTypeDerivedFromActionType() {
        CanonicalAction action = action("SALES_ORDER:CREATE", "erpnext", Map.of("customer", "Acme"));
        ConnectorExecutionResult result = connector.execute(request(action, route(null)));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Sales%20Order"),
                uriCaptor.getValue());
    }

    @Test
    @DisplayName("Document type can be derived from a space-separated action type")
    void documentTypeDerivedFromSpacedActionType() {
        CanonicalAction action = action("CREATE sales_order", "erpnext", Map.of("customer", "Acme"));
        ConnectorExecutionResult result = connector.execute(request(action, route(null)));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Sales%20Order"),
                uriCaptor.getValue());
    }

    @Test
    @DisplayName("Unsupported action fails closed without calling ERPNext")
    void unsupportedActionFailsClosed() {
        ConnectorExecutionResult result = connector.execute(
                request(action("READ", "quotation", Map.of("name", "QTN-0001")), route(null)));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("Unsupported ERPNext action type"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("Unsupported document type fails closed without calling ERPNext")
    void unsupportedDocumentTypeFailsClosed() {
        ConnectorExecutionResult result = connector.execute(
                request(action("CREATE", "erpnext", Map.of("customer", "Acme")), route(null)));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("document type"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("Resource connector configuration can narrow the allowed document types")
    void connectorConfigurationNarrowsDocumentTypes() {
        ResourceRoute restricted = route(null, Map.of("allowedDocTypes", List.of("Quotation")));
        ConnectorExecutionResult allowed = connector.execute(
                request(action("CREATE", "quotation", Map.of("party", "Acme")), restricted));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);
        assertTrue(allowed.isSuccessful());

        ConnectorExecutionResult rejected = connector.execute(
                request(action("CREATE", "customer", Map.of("customer_name", "Acme")), restricted));
        assertFalse(rejected.isSuccessful());
        assertTrue(rejected.getErrorMessage().contains("document type"));
    }

    @Test
    @DisplayName("Named-document actions fail closed without a document name")
    void namedActionRequiresDocumentName() {
        ConnectorExecutionResult result = connector.execute(
                request(action("UPDATE", "quotation", Map.of("party", "Acme")), route(null)));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("document name is required"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("Document identifiers containing path traversal are rejected")
    void invalidDocumentIdentifierRejected() {
        ConnectorExecutionResult result = connector.execute(
                request(action("UPDATE", "quotation", Map.of("name", "../../Customer/X")), route(null)));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("Invalid ERPNext document identifier"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("Configured HTTP method must match the ERPNext action method")
    void configuredHttpMethodMustMatch() {
        ConnectorExecutionResult result = connector.execute(
                request(action("CREATE", "quotation", Map.of("party", "Acme")), route("PUT")));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("Configured HTTP method"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("A matching configured HTTP method is accepted case-insensitively")
    void matchingConfiguredHttpMethodAccepted() {
        ConnectorExecutionResult result = connector.execute(
                request(action("UPDATE", "quotation", Map.of("name", "QTN-0001")), route("put")));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);
        assertTrue(result.isSuccessful());
        assertEquals(HttpMethod.PUT, methodCaptor.getValue());
    }

    @Test
    @DisplayName("ERPNext message envelope is unwrapped without losing response data")
    void messageEnvelopeUnwrapped() {
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "message", Map.of("name", "SINV-001", "status", "Draft"))));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertTrue(result.isSuccessful());
        assertEquals(Map.of("name", "SINV-001", "status", "Draft"), result.getResponseBody());
    }

    @Test
    @DisplayName("Responses without a message envelope are preserved")
    void responseWithoutEnvelopePreserved() {
        Map<String, Object> body = Map.of("data", List.of(Map.of("name", "QTN-0001")));
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(body));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertTrue(result.isSuccessful());
        assertEquals(body, result.getResponseBody());
    }

    @Test
    @DisplayName("Non-object message values are preserved in the full response")
    void nonObjectMessagePreserved() {
        Map<String, Object> body = Map.of("message", List.of("one", "two"));
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(body));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertTrue(result.isSuccessful());
        assertEquals(body, result.getResponseBody());
    }

    @Test
    @DisplayName("ERPNext client errors return the upstream status and a truncated message")
    void clientErrorHandled() {
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        HttpHeaders.EMPTY,
                        "{\"message\":\"Validation failed\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertFalse(result.isSuccessful());
        assertEquals(400, result.getStatusCode());
        assertTrue(result.getErrorMessage().contains("Validation failed"));
    }

    @Test
    @DisplayName("ERPNext server errors return the upstream status")
    void serverErrorHandled() {
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        "{\"exception\":\"Frappe boom\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertFalse(result.isSuccessful());
        assertEquals(500, result.getStatusCode());
        assertTrue(result.getErrorMessage().contains("Frappe boom"));
    }

    @Test
    @DisplayName("Timeouts and unreachable hosts are reported without throwing")
    void timeoutHandled() {
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("timed out"));
    }

    @Test
    @DisplayName("Unexpected response formats fail safely")
    void unexpectedResponseFormatHandled() {
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RestClientException("Cannot deserialize"));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("Unexpected response format"));
    }

    @Test
    @DisplayName("SSRF guard blocks the request before any network call")
    void ssrfGuardBlocksRequest() {
        when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(false);

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertFalse(result.isSuccessful());
        assertTrue(result.getErrorMessage().contains("SSRF"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("Missing or invalid base URLs fail closed")
    void invalidBaseUrlsFailClosed() {
        ResourceRoute missingBase = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("quotation")
                .connectorType(ConnectorType.ERPNEXT)
                .build();
        ConnectorExecutionResult missing = connector.execute(
                request(action("CREATE", "quotation", Map.of()), missingBase));
        assertFalse(missing.isSuccessful());
        assertTrue(missing.getErrorMessage().contains("no configured base URL"));

        ResourceRoute invalidBase = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("quotation")
                .connectorType(ConnectorType.ERPNEXT)
                .baseUrl("ftp://erpnext.example.com")
                .build();
        ConnectorExecutionResult invalid = connector.execute(
                request(action("CREATE", "quotation", Map.of()), invalidBase));
        assertFalse(invalid.isSuccessful());
        assertTrue(invalid.getErrorMessage().contains("invalid base URL"));
        verifyNoExchange();
    }

    @Test
    @DisplayName("Invalid execution requests fail closed")
    void invalidExecutionRequestsFailClosed() {
        assertFalse(connector.execute(null).isSuccessful());

        ConnectorExecutionRequest noAction = ConnectorExecutionRequest.builder()
                .route(route(null))
                .build();
        assertFalse(connector.execute(noAction).isSuccessful());

        ConnectorExecutionRequest noRoute = ConnectorExecutionRequest.builder()
                .action(action("CREATE", "quotation", Map.of()))
                .build();
        assertFalse(connector.execute(noRoute).isSuccessful());

        verifyNoExchange();
    }

    @Test
    @DisplayName("Action parameters cannot override the URL, path, host, or authentication headers")
    void actionParametersCannotOverrideTargetOrHeaders() {
        Map<String, Object> parameters = Map.of(
                "customer", "Acme",
                "url", "https://evil.example.com/steal",
                "baseUrl", "https://evil.example.com",
                "path", "/admin",
                "host", "evil.example.com",
                "headers", Map.of("X-Injected", "true"),
                "authorization", "Token attacker:token",
                "cookie", "sid=attacker");

        ConnectorExecutionResult result = connector.execute(createRequest(parameters));

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertEquals(URI.create("https://erpnext.example.com/api/resource/Quotation"), uriCaptor.getValue());

        HttpEntity<?> entity = entityCaptor.getValue();
        assertEquals("Token api_key:api_secret", entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(entity.getHeaders().getFirst("Cookie"));
        assertNull(entity.getHeaders().getFirst("X-Injected"));

        Map<String, Object> body = bodyOf(entity);
        assertEquals(Map.of("customer", "Acme"), body);
    }

    @Test
    @DisplayName("No resolved credentials means no Authorization header is set")
    void noCredentialsMeansNoAuthorizationHeader() {
        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-1")
                .action(action("CREATE", "quotation", Map.of("party", "Acme")))
                .route(route(null))
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        ArgumentCaptor<URI> uriCaptor = captureUri();
        ArgumentCaptor<HttpMethod> methodCaptor = captureMethod();
        ArgumentCaptor<HttpEntity<?>> entityCaptor = captureEntity();
        verifySingleExchange(uriCaptor, methodCaptor, entityCaptor);

        assertTrue(result.isSuccessful());
        assertNull(entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("Credentials containing CRLF are rejected before any network call")
    void crlfCredentialsRejected() {
        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-1")
                .action(action("CREATE", "quotation", Map.of("party", "Acme")))
                .route(route(null))
                .resolvedCredentials(Map.of("Authorization", "Token key:secret\r\nX-Injected: true"))
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        assertFalse(result.isSuccessful());
        verifyNoExchange();
    }

    @Test
    @DisplayName("Errors never echo credential values")
    void errorsDoNotEchoCredentials() {
        when(restTemplate.exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED,
                        "Unauthorized",
                        HttpHeaders.EMPTY,
                        "{\"message\":\"Not permitted\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        ConnectorExecutionResult result = connector.execute(createRequest(Map.of("customer", "Acme")));

        assertFalse(result.isSuccessful());
        assertFalse(result.getErrorMessage().contains("api_secret"));
        assertFalse(result.getErrorMessage().contains("api_key"));
    }

    private void verifyNoExchange() {
        verify(restTemplate, never()).exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
        verify(restTemplate, never()).exchange(
                anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }
}
