package io.netherite.connectors.aierp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.support.http.NoRedirectJdkClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-grade ResourceConnector for AI-ERP.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Action-specific API routing (exact HTTP methods, path templates, and parameter serializing)</li>
 *   <li>Secure JWT lifecycle via {@link AiErpTokenService} (with single 401 token refresh retry)</li>
 *   <li>Application-level success/failure interpretation (even on HTTP 200)</li>
 *   <li>SSRF protection via {@link SsrfGuardValidator}</li>
 *   <li>Response size limits and safe error redaction</li>
 * </ul>
 */
@Slf4j
@Component
public class AiErpResourceConnector implements ResourceConnector {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static final int MAX_RESPONSE_CHARS = 500_000;
    private static final int MAX_ERROR_DETAIL_LENGTH = 500;

    private final RestTemplate restTemplate;
    private final SsrfGuardValidator ssrfGuardValidator;
    private final AiErpTokenService tokenService;
    private final AiErpActionDefinitionRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiErpResourceConnector(
            SsrfGuardValidator ssrfGuardValidator,
            AiErpTokenService tokenService,
            AiErpActionDefinitionRegistry registry) {
        this.ssrfGuardValidator = ssrfGuardValidator;
        this.tokenService = tokenService;
        this.registry = registry;

        NoRedirectJdkClientHttpRequestFactory factory =
                new NoRedirectJdkClientHttpRequestFactory(Duration.ofSeconds(5), Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public ConnectorType getConnectorType() {
        return ConnectorType.AI_ERP;
    }

    @Override
    public ConnectorExecutionResult execute(ConnectorExecutionRequest request) {
        long start = System.nanoTime();

        if (request == null || request.getRoute() == null || request.getAction() == null) {
            return failure(start, "Invalid execution request: missing required fields");
        }

        CanonicalAction action = request.getAction();
        ResourceRoute route = request.getRoute();
        String baseUrl = route.getBaseUrl();

        if (baseUrl == null || baseUrl.isBlank()) {
            return failure(start, "Resource route has no configured base URL");
        }

        URI baseUri = parseBaseUri(baseUrl);
        if (baseUri == null) {
            return failure(start, "Resource route has an invalid base URL");
        }

        // 1. Resolve path template and HTTP method
        String pathTemplate = route.getPathTemplate();
        String httpMethodStr = route.getHttpMethod();

        if (pathTemplate == null || pathTemplate.isBlank() || httpMethodStr == null || httpMethodStr.isBlank()) {
            Optional<AiErpActionDefinition> defOpt = registry.findDefinition(action.getActionType());
            if (defOpt.isPresent()) {
                AiErpActionDefinition def = defOpt.get();
                if (pathTemplate == null || pathTemplate.isBlank()) {
                    pathTemplate = def.getPathTemplate();
                }
                if (httpMethodStr == null || httpMethodStr.isBlank()) {
                    httpMethodStr = def.getHttpMethod();
                }
            } else {
                return failure(start, "Unregistered or unsupported AI-ERP action: " + action.getActionType());
            }
        }

        HttpMethod httpMethod;
        try {
            httpMethod = HttpMethod.valueOf(httpMethodStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return failure(start, "Unsupported HTTP method for action: " + httpMethodStr);
        }

        // 2. Build target URI by interpolating path parameters and appending query parameters for GET
        Map<String, Object> params = action.getParameters() != null ? new LinkedHashMap<>(action.getParameters()) : new LinkedHashMap<>();
        URI targetUri;
        try {
            targetUri = buildTargetUri(baseUri, pathTemplate, httpMethod, params);
        } catch (IllegalArgumentException e) {
            return failure(start, "Failed to construct target URL: " + e.getMessage());
        }

        // Validate origin matches registered base URI (prevent host pollution / SSRF bypass)
        if (!isSameOrigin(targetUri, baseUri)) {
            log.error("AI-ERP connector detected destination host mismatch for execution {}", request.getExecutionId());
            return failure(start, "Destination URL does not match registered resource host");
        }

        // SSRF check on target URI
        if (!ssrfGuardValidator.isUriPermitted(targetUri)) {
            log.error("AI-ERP connector destination blocked by SSRF guard for execution {}: {}", request.getExecutionId(), targetUri);
            return failure(start, "Destination URL failed SSRF validation");
        }

        // 3. Acquire initial JWT token
        String token;
        try {
            token = tokenService.getValidToken(
                    baseUrl,
                    request.getResolvedCredentials(),
                    action.getOrganizationId(),
                    route.getResourceId());
        } catch (Exception e) {
            log.error("Failed to acquire AI-ERP token for execution {}: {}", request.getExecutionId(), e.getMessage());
            return failure(start, "Authentication failed: " + e.getMessage());
        }

        // 4. Execute request with transparent 401 retry (at most 1 retry)
        try {
            ResponseEntity<String> response = sendRequest(targetUri, httpMethod, params, token, request);

            // Handle HTTP 401 with one re-authentication / refresh attempt
            if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.info("Received 401 from AI-ERP for execution {}. Attempting token refresh and single retry.", request.getExecutionId());
                try {
                    token = tokenService.refreshTokenOrReacquire(
                            baseUrl,
                            request.getResolvedCredentials(),
                            action.getOrganizationId(),
                            route.getResourceId());
                    response = sendRequest(targetUri, httpMethod, params, token, request);
                } catch (Exception refreshEx) {
                    log.warn("Token reacquisition failed after 401: {}", refreshEx.getMessage());
                    return failure(start, "AI-ERP token reacquisition failed after 401: " + refreshEx.getMessage());
                }
            }

            return parseResponse(start, response);

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                // Attempt one token refresh and retry
                log.info("Caught 401 HttpStatusCodeException for execution {}. Attempting refresh and retry.", request.getExecutionId());
                try {
                    String newToken = tokenService.refreshTokenOrReacquire(
                            baseUrl,
                            request.getResolvedCredentials(),
                            action.getOrganizationId(),
                            route.getResourceId());
                    ResponseEntity<String> retryResponse = sendRequest(targetUri, httpMethod, params, newToken, request);
                    return parseResponse(start, retryResponse);
                } catch (Exception retryEx) {
                    log.error("Retry after 401 failed for execution {}: {}", request.getExecutionId(), retryEx.getMessage());
                }
            }
            long durationMs = durationMillis(start);
            log.warn("AI-ERP HTTP error for execution {}: status {}", request.getExecutionId(), e.getStatusCode().value());
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .statusCode(e.getStatusCode().value())
                    .errorMessage("AI-ERP HTTP error: " + extractErrorDetail(e))
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (ResourceAccessException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            String diagnostic = describe(cause);
            if (isProtocolError(cause)) {
                log.error("AI-ERP HTTP client protocol error for execution {}: {}",
                        request.getExecutionId(), diagnostic);
                return failure(start, "AI-ERP HTTP client protocol error: " + diagnostic);
            }
            if (isTimeout(cause)) {
                log.error("AI-ERP request timed out for execution {}: {}",
                        request.getExecutionId(), diagnostic);
                return failure(start, "AI-ERP request timed out: " + diagnostic);
            }
            log.error("AI-ERP resource unreachable for execution {}: {}",
                    request.getExecutionId(), diagnostic);
            return failure(start, "AI-ERP resource unreachable: " + diagnostic);

        } catch (RestClientException e) {
            log.error("AI-ERP returned an unexpected response for execution {}", request.getExecutionId());
            return failure(start, "Unexpected response format from AI-ERP: " + e.getMessage());

        } catch (Exception e) {
            log.error("AI-ERP connector execution failed for execution {}", request.getExecutionId(), e);
            return failure(start, "AI-ERP connector execution failed: " + e.getMessage());
        }
    }

    private ResponseEntity<String> sendRequest(
            URI targetUri,
            HttpMethod method,
            Map<String, Object> remainingParams,
            String token,
            ConnectorExecutionRequest request) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(token);

        if (request.getExecutionId() != null && !request.getExecutionId().isBlank()) {
            headers.set("X-Netherite-Execution-Id", request.getExecutionId());
        }
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            headers.set("X-Netherite-Idempotency-Key", request.getIdempotencyKey());
        }

        HttpEntity<?> entity;
        if (method == HttpMethod.GET || method == HttpMethod.DELETE && remainingParams.isEmpty()) {
            entity = new HttpEntity<>(headers);
        } else {
            entity = new HttpEntity<>(remainingParams, headers);
        }

        return restTemplate.exchange(targetUri, method, entity, String.class);
    }

    private ConnectorExecutionResult parseResponse(long start, ResponseEntity<String> response) {
        long durationMs = durationMillis(start);
        int statusCode = response.getStatusCode().value();
        String bodyStr = response.getBody();

        if (bodyStr == null || bodyStr.isBlank()) {
            boolean httpSuccess = response.getStatusCode().is2xxSuccessful();
            return ConnectorExecutionResult.builder()
                    .successful(httpSuccess)
                    .statusCode(statusCode)
                    .responseBody(Map.of())
                    .errorMessage(httpSuccess ? null : "AI-ERP returned HTTP " + statusCode + " with empty body")
                    .executionDurationMillis(durationMs)
                    .build();
        }

        if (bodyStr.length() > MAX_RESPONSE_CHARS) {
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .statusCode(statusCode)
                    .errorMessage("AI-ERP response exceeded maximum allowable size (" + bodyStr.length() + " chars)")
                    .executionDurationMillis(durationMs)
                    .build();
        }

        try {
            JsonNode root = objectMapper.readTree(bodyStr);

            // AI-ERP application-level contract check:
            // "success": true / false (case-insensitive)
            boolean appSuccess = true;
            if (root.has("success")) {
                appSuccess = root.get("success").asBoolean(true);
            } else if (root.has("Success")) {
                appSuccess = root.get("Success").asBoolean(true);
            }

            boolean overallSuccess = response.getStatusCode().is2xxSuccessful() && appSuccess;

            Map<String, Object> responseMap;
            if (root.isObject()) {
                responseMap = objectMapper.convertValue(root, Map.class);
            } else {
                responseMap = Map.of("data", root.asText());
            }

            String errorMessage = null;
            if (!overallSuccess) {
                if (root.has("message") && !root.get("message").asText().isBlank()) {
                    errorMessage = root.get("message").asText();
                } else if (root.has("error") && !root.get("error").asText().isBlank()) {
                    errorMessage = root.get("error").asText();
                } else {
                    errorMessage = "AI-ERP operation failed with HTTP " + statusCode;
                }
            }

            return ConnectorExecutionResult.builder()
                    .successful(overallSuccess)
                    .statusCode(statusCode)
                    .responseBody(responseMap)
                    .errorMessage(errorMessage)
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse AI-ERP JSON response: {}", e.getMessage());
            boolean httpSuccess = response.getStatusCode().is2xxSuccessful();
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .statusCode(statusCode)
                    .errorMessage("Malformed JSON response from AI-ERP: " + e.getMessage())
                    .executionDurationMillis(durationMs)
                    .build();
        }
    }

    private URI buildTargetUri(URI baseUri, String pathTemplate, HttpMethod method, Map<String, Object> params) {
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        // Interpolate {param} in pathTemplate from params
        Matcher matcher = PATH_PARAM_PATTERN.matcher(pathTemplate);
        StringBuffer pathBuffer = new StringBuffer();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            Object paramValue = params.remove(paramName);
            if (paramValue == null) {
                throw new IllegalArgumentException("Missing required path parameter: " + paramName);
            }
            String encodedVal = URLEncoder.encode(String.valueOf(paramValue), StandardCharsets.UTF_8);
            matcher.appendReplacement(pathBuffer, Matcher.quoteReplacement(encodedVal));
        }
        matcher.appendTail(pathBuffer);

        String resolvedPath = pathBuffer.toString();
        if (!resolvedPath.startsWith("/")) {
            resolvedPath = "/" + resolvedPath;
        }

        String fullUrl = base + resolvedPath;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(fullUrl);

        // If GET method, remaining parameters are attached as query parameters
        if (method == HttpMethod.GET && !params.isEmpty()) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    builder.queryParam(entry.getKey(), entry.getValue());
                }
            }
        }

        return builder.build().encode().toUri();
    }

    private boolean isSameOrigin(URI targetUri, URI baseUri) {
        return targetUri.getHost() != null
                && baseUri.getHost() != null
                && targetUri.getHost().equalsIgnoreCase(baseUri.getHost())
                && targetUri.getPort() == baseUri.getPort()
                && targetUri.getScheme() != null
                && targetUri.getScheme().equalsIgnoreCase(baseUri.getScheme());
    }

    private URI parseBaseUri(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null || scheme == null) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractErrorDetail(HttpStatusCodeException exception) {
        String responseBody = exception.getResponseBodyAsString();
        if (responseBody == null || responseBody.isBlank()) {
            return exception.getStatusText() != null ? exception.getStatusText() : String.valueOf(exception.getStatusCode().value());
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("message")) return truncate(root.get("message").asText());
            if (root.has("error")) return truncate(root.get("error").asText());
        } catch (Exception ignored) {
        }
        return truncate(responseBody);
    }

    private String truncate(String val) {
        if (val == null) return "";
        String s = val.replaceAll("\\s+", " ").trim();
        return s.length() <= MAX_ERROR_DETAIL_LENGTH ? s : s.substring(0, MAX_ERROR_DETAIL_LENGTH);
    }

    private String describe(Throwable cause) {
        String message = cause.getMessage();
        String description = cause.getClass().getSimpleName();
        if (message != null && !message.isBlank()) {
            description += ": " + truncate(message);
        }
        return description;
    }

    private boolean isProtocolError(Throwable cause) {
        return cause instanceof ProtocolException
                || cause instanceof IllegalArgumentException
                || cause instanceof UnsupportedOperationException
                || containsIgnoreCase(cause.getMessage(), "invalid http method")
                || containsIgnoreCase(cause.getMessage(), "method not supported");
    }

    private boolean isTimeout(Throwable cause) {
        return cause instanceof HttpTimeoutException
                || cause instanceof SocketTimeoutException
                || containsIgnoreCase(cause.getMessage(), "timed out")
                || containsIgnoreCase(cause.getMessage(), "timeout");
    }

    private boolean containsIgnoreCase(String value, String token) {
        return value != null && value.toLowerCase().contains(token);
    }

    private ConnectorExecutionResult failure(long start, String message) {
        return ConnectorExecutionResult.builder()
                .successful(false)
                .errorMessage(message)
                .executionDurationMillis(durationMillis(start))
                .build();
    }

    private long durationMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
