package io.netherite.connectors.erpnext;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.model.CanonicalAction;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.routing.model.ResourceRoute;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ErpNextResourceConnector implements ResourceConnector {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of("CREATE", "UPDATE", "SUBMIT", "DELETE");

    private static final Map<String, String> SUPPORTED_DOCUMENT_TYPES = Map.of(
            "QUOTATION", "Quotation",
            "SALES_ORDER", "Sales Order",
            "SALES_INVOICE", "Sales Invoice",
            "CUSTOMER", "Customer",
            "ITEM", "Item",
            "LEAD", "Lead",
            "OPPORTUNITY", "Opportunity");

    private static final List<String> DOCUMENT_NAME_KEYS =
            List.of("name", "document_name", "documentName", "id");

    private static final Set<String> BODY_CONTROL_KEYS = Set.of(
            "url", "uri", "path", "pathtemplate", "path_template",
            "baseurl", "base_url", "host", "method", "httpmethod", "http_method",
            "headers", "authorization", "cookie", "doctype", "document_type", "documenttype",
            "id", "document_name", "documentname");

    private static final Pattern DOCUMENT_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._\\-]{0,139}$");

    private static final int MAX_ERROR_DETAIL_LENGTH = 500;

    private final RestTemplate restTemplate;
    private final SsrfGuardValidator ssrfGuardValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ErpNextResourceConnector(SsrfGuardValidator ssrfGuardValidator) {
        this.ssrfGuardValidator = ssrfGuardValidator;
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public ConnectorType getConnectorType() {
        return ConnectorType.ERPNEXT;
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

        ParsedAction parsedAction = parseAction(action.getActionType());
        if (!SUPPORTED_ACTIONS.contains(parsedAction.verb())) {
            return failure(start, "Unsupported ERPNext action type: " + action.getActionType());
        }

        String documentType = resolveDocumentType(parsedAction.documentHint(), action.getTargetResource(),
                route.getConnectorConfig());
        if (documentType == null) {
            return failure(start, "Unsupported or unconfigured ERPNext document type");
        }

        String documentName = null;
        if (!"CREATE".equals(parsedAction.verb())) {
            documentName = extractDocumentName(action.getParameters());
            if (documentName == null) {
                return failure(start, "ERPNext document name is required for " + parsedAction.verb());
            }
            if (!DOCUMENT_NAME_PATTERN.matcher(documentName).matches()) {
                return failure(start, "Invalid ERPNext document identifier");
            }
        }

        HttpMethod httpMethod = methodFor(parsedAction.verb());
        if (!isMethodCompatible(route.getHttpMethod(), httpMethod)) {
            return failure(start, "Configured HTTP method does not match the ERPNext action");
        }

        URI targetUri = buildTargetUri(baseUri, documentType, documentName);
        if (!isUrlFromRegisteredResource(targetUri, baseUri)) {
            log.error("ERPNext connector detected URL mismatch for execution {}", request.getExecutionId());
            return failure(start, "Destination URL does not match registered resource");
        }

        if (!ssrfGuardValidator.isUriPermitted(targetUri)) {
            log.error("ERPNext connector blocked by SSRF guard for execution {}", request.getExecutionId());
            return failure(start, "Destination URL failed SSRF validation");
        }

        try {
            HttpHeaders headers = createHeaders(request);
            HttpEntity<?> entity = buildEntity(httpMethod, action.getParameters(), parsedAction.verb(), headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    targetUri, httpMethod, entity, Map.class);

            long durationMs = durationMillis(start);
            int statusCode = response.getStatusCode().value();
            boolean successful = response.getStatusCode().is2xxSuccessful();

            return ConnectorExecutionResult.builder()
                    .successful(successful)
                    .statusCode(statusCode)
                    .responseBody(unwrapResponse(response.getBody()))
                    .errorMessage(successful ? null : "ERPNext returned unsuccessful status: " + statusCode)
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (HttpStatusCodeException e) {
            long durationMs = durationMillis(start);
            log.warn("ERPNext HTTP error for execution {}: status {}",
                    request.getExecutionId(), e.getStatusCode().value());
            return ConnectorExecutionResult.builder()
                    .successful(false)
                    .statusCode(e.getStatusCode().value())
                    .errorMessage("ERPNext HTTP error: " + extractErrorDetail(e))
                    .executionDurationMillis(durationMs)
                    .build();

        } catch (ResourceAccessException e) {
            log.error("ERPNext request timed out or was unreachable for execution {}", request.getExecutionId());
            return failure(start, "ERPNext request timed out or the resource was unreachable");

        } catch (RestClientException e) {
            log.error("ERPNext returned an unexpected response for execution {}", request.getExecutionId());
            return failure(start, "Unexpected response format from ERPNext");

        } catch (Exception e) {
            log.error("ERPNext connector execution failed for execution {}", request.getExecutionId());
            return failure(start, "ERPNext connector execution failed");
        }
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

    private ParsedAction parseAction(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return new ParsedAction("", null);
        }

        String raw = actionType.trim();
        int colon = raw.lastIndexOf(':');
        if (colon >= 0) {
            return new ParsedAction(
                    raw.substring(colon + 1).trim().toUpperCase(Locale.ROOT),
                    blankToNull(raw.substring(0, colon)));
        }

        String[] tokens = raw.split("\\s+");
        if (tokens.length >= 2) {
            String first = tokens[0].toUpperCase(Locale.ROOT);
            String last = tokens[tokens.length - 1].toUpperCase(Locale.ROOT);
            if (SUPPORTED_ACTIONS.contains(first)) {
                return new ParsedAction(first, blankToNull(raw.substring(tokens[0].length())));
            }
            if (SUPPORTED_ACTIONS.contains(last)) {
                return new ParsedAction(last,
                        blankToNull(raw.substring(0, raw.length() - tokens[tokens.length - 1].length())));
            }
        }

        String upper = raw.toUpperCase(Locale.ROOT);
        for (String verb : SUPPORTED_ACTIONS) {
            if (upper.startsWith(verb + "_")) {
                return new ParsedAction(verb, blankToNull(raw.substring(verb.length() + 1)));
            }
            if (upper.endsWith("_" + verb)) {
                return new ParsedAction(verb, blankToNull(raw.substring(0, raw.length() - verb.length() - 1)));
            }
        }

        return new ParsedAction(upper, null);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String resolveDocumentType(String hint, String targetResource, Map<String, Object> connectorConfig) {
        Set<String> allowedKeys = configuredDocumentTypeKeys(connectorConfig);
        String keyFromHint = canonicalKey(hint);
        if (keyFromHint != null && allowedKeys.contains(keyFromHint)) {
            return SUPPORTED_DOCUMENT_TYPES.get(keyFromHint);
        }
        String keyFromResource = canonicalKey(targetResource);
        if (keyFromResource != null && allowedKeys.contains(keyFromResource)) {
            return SUPPORTED_DOCUMENT_TYPES.get(keyFromResource);
        }
        return null;
    }

    private Set<String> configuredDocumentTypeKeys(Map<String, Object> connectorConfig) {
        if (connectorConfig == null) {
            return SUPPORTED_DOCUMENT_TYPES.keySet();
        }

        Object configured = firstPresent(connectorConfig,
                "allowedDocTypes", "allowed_doc_types", "erpnext.allowedDocTypes");
        if (configured == null) {
            return SUPPORTED_DOCUMENT_TYPES.keySet();
        }

        Collection<?> values;
        if (configured instanceof Collection<?> collection) {
            values = collection;
        } else if (configured.getClass().isArray()) {
            values = List.of((Object[]) configured);
        } else {
            values = List.of(String.valueOf(configured).split(","));
        }

        Set<String> keys = new LinkedHashSet<>();
        for (Object value : values) {
            String key = canonicalKey(value == null ? null : String.valueOf(value));
            if (key != null && SUPPORTED_DOCUMENT_TYPES.containsKey(key)) {
                keys.add(key);
            } else {
                log.warn("Ignoring unsupported ERPNext document type in resource connector configuration");
            }
        }
        return keys;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String canonicalKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return normalized.isBlank() ? null : normalized;
    }

    private String extractDocumentName(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        for (String key : DOCUMENT_NAME_KEYS) {
            Object value = parameters.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private HttpMethod methodFor(String verb) {
        return switch (verb) {
            case "CREATE" -> HttpMethod.POST;
            case "UPDATE", "SUBMIT" -> HttpMethod.PUT;
            case "DELETE" -> HttpMethod.DELETE;
            default -> null;
        };
    }

    private boolean isMethodCompatible(String configuredMethod, HttpMethod expectedMethod) {
        if (configuredMethod == null || configuredMethod.isBlank()) {
            return true;
        }
        return expectedMethod.name().equalsIgnoreCase(configuredMethod.trim());
    }

    private URI buildTargetUri(URI baseUri, String documentType, String documentName) {
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base)
                .pathSegment("api", "resource", documentType);
        if (documentName != null) {
            builder.pathSegment(documentName);
        }
        return builder.build().encode().toUri();
    }

    private boolean isUrlFromRegisteredResource(URI targetUri, URI baseUri) {
        String targetHost = targetUri.getHost();
        String baseHost = baseUri.getHost();
        return targetHost != null && baseHost != null && targetHost.equalsIgnoreCase(baseHost);
    }

    private HttpHeaders createHeaders(ConnectorExecutionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        if (request.getExecutionId() != null && !request.getExecutionId().isBlank()) {
            headers.set("X-Netherite-Execution-Id", request.getExecutionId());
        }
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            headers.set("X-Netherite-Idempotency-Key", request.getIdempotencyKey());
        }

        Map<String, String> credentials = request.getResolvedCredentials();
        if (credentials != null) {
            String authorization = credentials.get("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                String value = authorization.trim();
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("Invalid authorization credential");
                }
                headers.set(HttpHeaders.AUTHORIZATION, value);
            }
        }

        return headers;
    }

    private HttpEntity<?> buildEntity(HttpMethod method, Map<String, Object> parameters,
                                      String verb, HttpHeaders headers) {
        if (method == HttpMethod.DELETE) {
            return new HttpEntity<>(headers);
        }
        return new HttpEntity<>(sanitizeBody(parameters, verb), headers);
    }

    private Map<String, Object> sanitizeBody(Map<String, Object> parameters, String verb) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                String key = entry.getKey();
                if (key == null || isRemovedBodyKey(key, verb)) {
                    continue;
                }
                body.put(key, entry.getValue());
            }
        }

        if ("SUBMIT".equals(verb)) {
            body.put("docstatus", 1);
        }
        return body;
    }

    private boolean isRemovedBodyKey(String key, String verb) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (BODY_CONTROL_KEYS.contains(lower) || "docstatus".equals(lower)) {
            return true;
        }
        return !"CREATE".equals(verb) && DOCUMENT_NAME_KEYS.stream()
                .anyMatch(nameKey -> nameKey.equalsIgnoreCase(key));
    }

    private Map<String, Object> unwrapResponse(Map<?, ?> responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return Map.of();
        }

        Object message = responseBody.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            return toStringKeyedMap(responseBody);
        }
        return toStringKeyedMap(messageMap);
    }

    private Map<String, Object> toStringKeyedMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private String extractErrorDetail(HttpStatusCodeException exception) {
        String statusText = exception.getStatusText();
        String responseBody = exception.getResponseBodyAsString();
        if (responseBody == null || responseBody.isBlank()) {
            return statusText == null || statusText.isBlank()
                    ? String.valueOf(exception.getStatusCode().value())
                    : statusText;
        }

        try {
            Map<?, ?> parsed = objectMapper.readValue(responseBody, Map.class);
            Object detail = firstPresent(unwrapKeys(parsed), "message", "exception", "_server_messages");
            if (detail != null) {
                return truncate(String.valueOf(detail));
            }
        } catch (Exception ignored) {
        }
        return truncate(responseBody);
    }

    private Map<String, Object> unwrapKeys(Map<?, ?> source) {
        return toStringKeyedMap(source);
    }

    private String truncate(String value) {
        String collapsed = value.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= MAX_ERROR_DETAIL_LENGTH) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_ERROR_DETAIL_LENGTH);
    }

    private record ParsedAction(String verb, String documentHint) {
    }
}
