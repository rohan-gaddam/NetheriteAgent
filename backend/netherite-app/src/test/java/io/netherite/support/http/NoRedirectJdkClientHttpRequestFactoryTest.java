package io.netherite.support.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NoRedirectJdkClientHttpRequestFactoryTest {

    private HttpServer server;
    private RestTemplate restTemplate;
    private String baseUrl;
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> {
            redirectTargetHits.incrementAndGet();
            sendJson(exchange, 200, "{\"followed\":true}");
        });
        server.createContext("/", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendJson(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        restTemplate = new RestTemplate(
                new NoRedirectJdkClientHttpRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("Mutating HTTP methods are sent with their method and body intact")
    void mutatingMethodsAreSentWithBody(String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"credit_limit\":9500}";

        ResponseEntity<String> response = restTemplate.exchange(
                URI.create(baseUrl + "/records/customer/1"),
                HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"ok\":true");
        assertThat(capturedMethod.get()).isEqualTo(method);
        assertThat(capturedBody.get()).isEqualTo(body);
    }

    @Test
    @DisplayName("Redirects are not followed")
    void redirectsAreNotFollowed() {
        ResponseEntity<String> response = restTemplate.exchange(
                URI.create(baseUrl + "/redirect"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(redirectTargetHits.get()).isZero();
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
