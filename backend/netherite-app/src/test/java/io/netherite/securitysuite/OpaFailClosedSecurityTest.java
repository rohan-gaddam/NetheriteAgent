package io.netherite.securitysuite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.DecisionType;
import io.netherite.context.model.DecisionContext;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.policy.client.OpaPolicyEngineClientImpl;
import io.netherite.policy.model.PolicyEvaluationResult;
import io.netherite.policy.service.PolicyEvaluationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Batch H — dedicated security suite.
 *
 * <p>Fail-closed contract of {@link OpaPolicyEngineClientImpl} exercised against a
 * local {@code com.sun.net.httpserver.HttpServer} stub: no Docker, no mocks at the
 * security boundary. Adversarial (malformed / contradictory / unavailable) engine
 * responses must never yield an ALLOW — either the client marks the evaluation
 * unsuccessful or {@link PolicyEvaluationService#resolvePrecedence} resolves to
 * DENY for any payload that cannot positively prove an allow.</p>
 */
class OpaFailClosedSecurityTest {

    private HttpServer server;
    private volatile byte[] responseBody = "{\"result\":{}}".getBytes(StandardCharsets.UTF_8);
    private volatile int responseStatus = 200;
    private volatile int receivedRequests = 0;
    private int port;

    private static final DecisionContext CONTEXT = DecisionContext.builder()
            .contextId("ctx-failclosed")
            .action(CanonicalAction.builder()
                    .actionId("act-failclosed")
                    .actionType("PAYMENT:REFUND")
                    .targetResource("invoice:42")
                    .parameters(Map.of())
                    .userId("user-7")
                    .agentId("agent-7")
                    .organizationId("org-7")
                    .timestamp(Instant.now())
                    .build())
            .businessFacts(Map.of())
            .stateFacts(Map.of())
            .securitySignals(Map.of())
            .agentMetrics(Map.of())
            .assembledAt(Instant.now())
            .build();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedRequests++;
        byte[] raw = new byte[Math.max(1, exchange.getRequestBody().available())];
        exchange.getRequestBody().readNBytes(raw.length);
        byte[] body = responseBody;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpaPolicyEngineClientImpl client() {
        return new OpaPolicyEngineClientImpl("http://127.0.0.1:" + port, "/v1/data/netherite/authz", 2000);
    }

    private void respond(int status, String json) {
        this.responseStatus = status;
        this.responseBody = json.getBytes(StandardCharsets.UTF_8);
        this.receivedRequests = 0;
    }

    /**
     * Resolution of a raw OPA payload into a final decision via the same precedence
     * (DENY > HUMAN_APPROVAL > ALLOW, and DENY for anything inconclusive) the runtime uses.
     */
    private DecisionType resolve(PolicyEvaluationResult result) {
        PolicyEvaluationService service = new PolicyEvaluationService(null, client());
        return service.resolvePrecedence(result);
    }

    @Test
    @DisplayName("OPA-1. Well-formed DENY payload resolves to DENY")
    void denyPayloadResolvesToDeny() {
        respond(200, "{\"result\":{\"decision\":\"deny\",\"denyReasons\":[\"Payment exceeds policy limit\"]}}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getRuleResults()).hasSize(1);
        assertThat(resolve(result)).isEqualTo(DecisionType.DENY);
    }

    @Test
    @DisplayName("OPA-2. Well-formed ALLOW payload resolves to ALLOW")
    void allowPayloadResolvesToAllow() {
        respond(200, "{\"result\":{\"decision\":\"allow\",\"allowReasons\":[\"Verified low-risk action\"]}}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(resolve(result)).isEqualTo(DecisionType.ALLOW);
    }

    @Test
    @DisplayName("OPA-3. Contradictory engine reply denying while claiming allow resolves to DENY")
    void denyAndAllowMixedPayloadResolvesToDeny() {
        respond(200, "{\"result\":{\"decision\":\"allow\","
                + "\"denyReasons\":[\"Policy denied the action\"],\"allowReasons\":[\"Some allow rule\"]}}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(resolve(result)).isEqualTo(DecisionType.DENY);
    }

    @Test
    @DisplayName("OPA-4. APPROVAL payload (even with allow reasons) resolves to HUMAN_APPROVAL")
    void approvalPayloadPrecedesAllow() {
        respond(200, "{\"result\":{\"decision\":\"approval\","
                + "\"approvalReasons\":[\"High trust tier requires human approval\"],\"allowReasons\":[\"Some allow\"]}}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(resolve(result)).isEqualTo(DecisionType.HUMAN_APPROVAL);
    }

    @Test
    @DisplayName("OPA-5. Advisory decision field without reasons cannot grant access")
    void advisoryDecisionFieldCannotGrantWithoutReasons() {
        respond(200, "{\"result\":{\"decision\":\"allow\"}}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(resolve(result)).isEqualTo(DecisionType.DENY);
    }

    @Test
    @DisplayName("OPA-6. Empty result set fails closed to DENY")
    void emptyResultFailsClosedToDeny() {
        respond(200, "{\"result\":{}}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getRuleResults()).isEmpty();
        assertThat(resolve(result)).isEqualTo(DecisionType.DENY);
    }

    @Test
    @DisplayName("OPA-7. Non-map result payload fails closed (unsuccessful)")
    void nonMapResultFailsClosed() {
        respond(200, "{\"result\":[\"allow\"]}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).contains("not a map");
    }

    @Test
    @DisplayName("OPA-8. Missing result key fails closed (unsuccessful)")
    void nullResultFailsClosed() {
        respond(200, "{\"other\":true}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).contains("null result");
    }

    @Test
    @DisplayName("OPA-9. Malformed engine payload fails closed (unsuccessful)")
    void malformedJsonFailsClosed() {
        respond(200, "not-json-at-all");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    @DisplayName("OPA-10. 500 from the engine fails closed (unsuccessful), no exception")
    void internalServerErrorFailsClosed() {
        respond(500, "{\"error\":\"internal\"}");
        PolicyEvaluationResult result = client().evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    @DisplayName("OPA-11. Unreachable engine fails closed without letting an exception escape")
    void unreachableEngineFailsClosedWithoutThrowing() throws IOException {
        OpaPolicyEngineClientImpl infraClient =
                new OpaPolicyEngineClientImpl("http://127.0.0.1:1", "/v1/data/netherite/authz", 150);
        PolicyEvaluationResult result = assertDoesNotThrow(() -> infraClient.evaluate(CONTEXT));
        assertThat(result.isSuccessful()).isFalse();
        assertThat(infraClient.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("OPA-12. SSRF-blocked OPA endpoint fails closed and never issues a request")
    void ssrfBlockedOpaUrlFailsClosed() {
        SsrfGuardValidator guard = new SsrfGuardValidator() {
            @Override
            public boolean isUriPermitted(URI targetUri) {
                return false;
            }
        };
        OpaPolicyEngineClientImpl guardedClient = new OpaPolicyEngineClientImpl(
                "http://127.0.0.1:" + port, "/v1/data/netherite/authz", 2000, guard, null);

        receivedRequests = 0;
        PolicyEvaluationResult result = guardedClient.evaluate(CONTEXT);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).contains("OPA endpoint failed SSRF validation");
        assertThat(guardedClient.isHealthy()).isFalse();
        assertThat(receivedRequests).isZero();
    }

    @Test
    @DisplayName("OPA-13. Null rule set resolves to DENY (defense in depth on the precedence function)")
    void nullRulesResolveToDeny() {
        PolicyEvaluationService service = new PolicyEvaluationService(null, client());
        PolicyEvaluationResult empty = PolicyEvaluationResult.builder()
                .successful(true)
                .ruleResults(List.of())
                .build();
        assertThat(service.resolvePrecedence(empty)).isEqualTo(DecisionType.DENY);
    }
}