package io.netherite.policy.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netherite.common.model.CanonicalAction;
import io.netherite.context.model.DecisionContext;
import io.netherite.policy.model.PolicyEvaluationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the OPA HTTP client attaches the bearer credential whenever configured
 * and fails closed (unsuccessful) when the engine rejects the request with 401 —
 * the exact behavior an enforcement-mode engine presents to an unauthenticated
 * caller.
 */
class OpaPolicyEngineClientAuthTest {

    private HttpServer server;
    private int port;
    private final List<String> receivedAuthorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] raw = new byte[Math.max(1, exchange.getRequestBody().available())];
        exchange.getRequestBody().readNBytes(raw.length);
        receivedAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        // The /authzone namespace simulates an enforcement-mode engine that demands
        // the exact bearer credential on every request.
        if (exchange.getRequestURI().getPath().startsWith("/authzone")) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer expected-token".equals(auth)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
        }
        byte[] body = "{\"result\":{}}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static DecisionContext context() {
        return DecisionContext.builder()
                .contextId("ctx-1")
                .action(CanonicalAction.builder()
                        .actionId("act-1")
                        .actionType("GENERIC")
                        .targetResource("invoice:123")
                        .parameters(Map.of())
                        .userId("user-1")
                        .agentId("agent-1")
                        .organizationId("org-1")
                        .timestamp(Instant.now())
                        .build())
                .businessFacts(Map.of())
                .stateFacts(Map.of("riskScore", 10))
                .securitySignals(Map.of())
                .agentMetrics(Map.of())
                .assembledAt(Instant.now())
                .build();
    }

    @Test
    void attachesBearerTokenToDecisionRequestsWhenConfigured() {
        OpaPolicyEngineClientImpl client =
                new OpaPolicyEngineClientImpl("http://127.0.0.1:" + port, "/authzone", 2000, "expected-token");

        PolicyEvaluationResult result = client.evaluate(context());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(receivedAuthorizations).contains("Bearer expected-token");
    }

    @Test
    void omitsAuthorizationHeaderWhenNoTokenConfigured() {
        OpaPolicyEngineClientImpl client =
                new OpaPolicyEngineClientImpl("http://127.0.0.1:" + port, "/authzone", 2000);

        client.evaluate(context());
        client.isHealthy();

        assertThat(receivedAuthorizations).allMatch(auth -> auth == null);
    }

    @Test
    void marksEvaluationUnsuccessfulWhenUnauthorized() {
        OpaPolicyEngineClientImpl client =
                new OpaPolicyEngineClientImpl("http://127.0.0.1:" + port, "/authzone", 2000);

        PolicyEvaluationResult result = client.evaluate(context());

        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    void healthCheckSendsToken() {
        OpaPolicyEngineClientImpl client =
                new OpaPolicyEngineClientImpl("http://127.0.0.1:" + port, "/v1/data/x", 2000, "expected-token");

        client.isHealthy();

        assertThat(receivedAuthorizations).contains("Bearer expected-token");
    }
}