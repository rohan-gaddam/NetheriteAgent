package io.netherite.connectors.aierp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.routing.model.ResourceRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AiErpResourceConnectorTransportTest {

    private static final String CUSTOMER_ID = "10727423-9f10-b258-ae5b-cadc0d302f65";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    private HttpServer server;
    private AiErpResourceConnector connector;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        SsrfGuardValidator ssrfGuardValidator = Mockito.mock(SsrfGuardValidator.class);
        when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(true);

        AiErpTokenService tokenService = Mockito.mock(AiErpTokenService.class);
        when(tokenService.getValidToken(any(), any(), any(), any())).thenReturn("valid-jwt");

        connector = new AiErpResourceConnector(
                ssrfGuardValidator, tokenService, new AiErpActionDefinitionRegistry());

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendJson(exchange, 200,
                    "{\"success\":true,\"record\":{\"id\":\"" + CUSTOMER_ID + "\",\"credit_limit\":9500}}");
        });
        server.start();

        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("UPDATE_RECORD sends a real PATCH request with the expected body")
    void updateRecordSendsPatch() throws Exception {
        CanonicalAction action = CanonicalAction.builder()
                .actionId("exec-update-1")
                .organizationId("00000000-0000-0000-0000-000000000001")
                .agentId("agent-1")
                .targetResource("ai-erp-prod")
                .actionType("AI_ERP:UPDATE_RECORD")
                .parameters(Map.of(
                        "entity", "customer",
                        "id", CUSTOMER_ID,
                        "credit_limit", 9500))
                .build();

        ResourceRoute route = ResourceRoute.builder()
                .resourceId("res-1")
                .resourceName("ai-erp-prod")
                .connectorType(ConnectorType.AI_ERP)
                .baseUrl(baseUrl)
                .pathTemplate("/api/v3.0/p/agent/records/{entity}/{id}")
                .httpMethod("PATCH")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .build();

        ConnectorExecutionRequest request = ConnectorExecutionRequest.builder()
                .executionId("exec-update-1")
                .idempotencyKey("idem-update-1")
                .action(action)
                .route(route)
                .resolvedCredentials(Map.of("email", "admin@erp.local", "password", "secret"))
                .timeoutMillis(30000)
                .build();

        ConnectorExecutionResult result = connector.execute(request);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getResponseBody()).containsEntry("success", true);
        assertThat(result.getResponseBody().get("record")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result.getResponseBody().get("record"))
                .containsEntry("credit_limit", 9500);

        assertThat(capturedMethod.get()).isEqualTo("PATCH");
        assertThat(capturedPath.get()).isEqualTo("/api/v3.0/p/agent/records/customer/" + CUSTOMER_ID);
        assertThat(capturedAuthorization.get()).isEqualTo("Bearer valid-jwt");

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertThat(body.path("credit_limit").asInt()).isEqualTo(9500);
        assertThat(body.has("entity")).isFalse();
        assertThat(body.has("id")).isFalse();
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
