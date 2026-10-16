package io.netherite.connectors.http;

import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic HTTP/REST resource connector for executing authorized actions against
 * enterprise resources via REST APIs. The registered resource's baseUrl is authoritative -
 * client input cannot override the destination URL.
 */
@Slf4j
@Component
public class HttpRestResourceConnector implements ResourceConnector {

    private final RestTemplate restTemplate;
    private final SsrfGuardValidator ssrfGuardValidator;

    public HttpRestResourceConnector(SsrfGuardValidator ssrfGuardValidator) {
        this.ssrfGuardValidator = ssrfGuardValidator;
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        // Redirects are never followed: a redirect could land off the SSRF-validated host
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public ConnectorType getConnectorType() {
        return ConnectorType.HTTP_REST;
    }

    @Override
    public ConnectorExecutionResult execute(ConnectorExecutionRequest request) {
        long start = System.nanoTime();

        if (request == null || request.getRoute() == null || request.getAction() == null) {
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .errorMessage("Invalid execution request: missing required fields")
                    .executionDurationMillis((System.nanoTime() - start) / 1_000_000)
                    .build();
        }

        String baseUrl = request.getRoute().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .errorMessage("Resource route has no configured base URL")
                    .executionDurationMillis((System.nanoTime() - start) / 1_000_000)
                    .build();
        }

        // Build destination URL from registered resource's baseUrl (authoritative)
        String targetUrl = buildTargetUrl(baseUrl, request);
        URI targetUri = URI.create(targetUrl);

        // Verify the resolved URL matches the registered resource's domain
        if (!isUrlFromRegisteredResource(targetUri, baseUrl)) {
            log.error("Connector detected URL mismatch: resolved '{}' does not match registered baseUrl '{}'",
                    targetUrl, baseUrl);
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .errorMessage("Destination URL does not match registered resource")
                    .executionDurationMillis((System.nanoTime() - start) / 1_000_000)
                    .build();
        }

        // SSRF guard re-validated at connect time (defense in depth against DNS rebinding
        // between the routing-layer check and the actual socket connect).
        if (!ssrfGuardValidator.isUriPermitted(targetUri)) {
            log.error("Connector execution blocked by SSRF guard: resolved '{}' for baseUrl '{}'",
                    targetUrl, baseUrl);
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .errorMessage("Destination URL failed SSRF validation")
                    .executionDurationMillis((System.nanoTime() - start) / 1_000_000)
                    .build();
        }

        try {
            HttpHeaders headers = createHeaders(request);
            Map<String, Object> body = request.getAction().getParameters();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body != null ? body : Map.of(), headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.POST,
                    entity,
                    Map.class);

            long durationMs = (System.nanoTime() - start) / 1_000_000;

            return ConnectorExecutionResult.builder()
                    .successful(response.getStatusCode().is2xxSuccessful())
                    .statusCode(response.getStatusCode().value())
                    .responseBody(response.getBody() != null ? response.getBody() : Map.of())
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("Connector HTTP error: {} - {}", e.getStatusCode(), e.getMessage());
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .statusCode(e.getStatusCode().value())
                    .errorMessage("HTTP error: " + e.getMessage())
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (ResourceAccessException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.error("Connector resource access error (timeout/unreachable): {}", e.getMessage());
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .errorMessage("Connector timeout or resource unreachable: " + e.getMessage())
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.error("Connector execution failed: {}", e.getMessage(), e);
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .errorMessage("Connector execution failed: " + e.getMessage())
                    .executionDurationMillis(durationMs)
                    .build();
        }
    }

    private String buildTargetUrl(String baseUrl, ConnectorExecutionRequest request) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = request.getRoute().getPathTemplate();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private boolean isUrlFromRegisteredResource(URI resolvedUri, String registeredBaseUrl) {
        try {
            URI registeredUri = URI.create(registeredBaseUrl);
            String resolvedHost = resolvedUri.getHost();
            String registeredHost = registeredUri.getHost();

            if (resolvedHost == null || registeredHost == null) {
                return false;
            }

            // Verify the resolved host matches or is a subdomain of the registered resource's host
            return resolvedHost.equalsIgnoreCase(registeredHost)
                    || resolvedHost.endsWith("." + registeredHost);
        } catch (Exception e) {
            log.error("Failed to validate URL against registered resource: {}", e.getMessage());
            return false;
        }
    }

    private HttpHeaders createHeaders(ConnectorExecutionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        // Correlation + deduplication headers for the external system: a crash-recovery
        // retry carries the same X-Netherite-Idempotency-Key so a compliant receiver can
        // avoid double-applying the effect.
        if (request.getExecutionId() != null && !request.getExecutionId().isBlank()) {
            headers.set("X-Netherite-Execution-Id", request.getExecutionId());
        }
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            headers.set("X-Netherite-Idempotency-Key", request.getIdempotencyKey());
        }

        if (request.getResolvedCredentials() != null) {
            String authHeader = request.getResolvedCredentials().get("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                headers.set("Authorization", authHeader);
            }
        }

        Map<String, String> connectorConfig = request.getRoute().getConnectorConfig() != null
                ? request.getRoute().getConnectorConfig().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue() != null ? e.getValue().toString() : ""))
                : Map.of();

        connectorConfig.forEach((key, value) -> {
            if (key.startsWith("header.")) {
                headers.set(key.substring("header.".length()), value);
            }
        });

        return headers;
    }
}
