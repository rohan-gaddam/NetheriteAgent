package io.netherite.connectors.aierp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the authentication lifecycle for AI-ERP's JWT-secured Agent API.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Obtain JWT access tokens via ERP login: {@code POST api/v3/en_US/auth/jwt/token}</li>
 *   <li>Refresh tokens via ERP refresh endpoint: {@code POST api/v3/en_US/auth/jwt/token/refresh}</li>
 *   <li>Concurrency-safe cached tokens per tenant and resource (strict tenant isolation)</li>
 *   <li>Never log passwords, access tokens, refresh tokens or authorization headers</li>
 *   <li>At most one recovery attempt per request (prevents infinite loops)</li>
 *   <li>Fail closed if credentials missing, invalid or unrefreshable</li>
 * </ul>
 */
@Slf4j
@Service
public class AiErpTokenService {

    private final RestTemplate restTemplate;
    private final SsrfGuardValidator ssrfGuardValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache of access tokens: key is "orgId:resourceId"
    private final Map<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();
    // Synchronization locks per cache key to prevent concurrent refresh races
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    private record TokenEntry(String token, Instant acquiredAt) {}

    public AiErpTokenService(SsrfGuardValidator ssrfGuardValidator) {
        this.ssrfGuardValidator = ssrfGuardValidator;
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Obtains a valid JWT access token for the given resource and credentials,
     * using the cache when available or acquiring a new token.
     */
    public String getValidToken(String baseUrl, Map<String, String> credentials, String orgId, String resourceId) {
        String cacheKey = orgId + ":" + resourceId;
        TokenEntry cached = tokenCache.get(cacheKey);
        if (cached != null && cached.token() != null && !cached.token().isBlank()) {
            return cached.token();
        }

        Object lock = keyLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            cached = tokenCache.get(cacheKey);
            if (cached != null && cached.token() != null && !cached.token().isBlank()) {
                return cached.token();
            }
            return acquireNewToken(baseUrl, credentials, cacheKey);
        }
    }

    /**
     * Refreshes or reacquires a token after a 401 or expiry.
     * Guaranteed to be concurrency-safe and will attempt refresh first, then reacquisition.
     */
    public String refreshTokenOrReacquire(String baseUrl, Map<String, String> credentials, String orgId, String resourceId) {
        String cacheKey = orgId + ":" + resourceId;
        Object lock = keyLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            TokenEntry current = tokenCache.get(cacheKey);
            if (current != null && current.token() != null) {
                try {
                    String refreshed = tryRefreshToken(baseUrl, current.token());
                    if (refreshed != null && !refreshed.isBlank()) {
                        tokenCache.put(cacheKey, new TokenEntry(refreshed, Instant.now()));
                        log.info("Successfully refreshed AI-ERP JWT token for resource {}", resourceId);
                        return refreshed;
                    }
                } catch (Exception e) {
                    log.warn("Token refresh failed for resource {}; falling back to credential reacquisition", resourceId);
                }
            }

            tokenCache.remove(cacheKey);
            return acquireNewToken(baseUrl, credentials, cacheKey);
        }
    }

    /**
     * Invalidates any cached token for this tenant and resource.
     */
    public void invalidateToken(String orgId, String resourceId) {
        String cacheKey = orgId + ":" + resourceId;
        tokenCache.remove(cacheKey);
    }

    private String acquireNewToken(String baseUrl, Map<String, String> credentials, String cacheKey) {
        if (credentials == null || credentials.isEmpty()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "No credentials configured for AI-ERP resource; cannot authenticate");
        }

        String email = credentials.get("email");
        if (email == null || email.isBlank()) {
            email = credentials.get("username");
        }
        if (email == null || email.isBlank()) {
            email = credentials.get("user");
        }

        String password = credentials.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Missing email/username or password in AI-ERP resource credentials");
        }

        String authPath = credentials.getOrDefault("authEndpoint", "api/v3/en_US/auth/jwt/token");
        String fullUrl = normalizeUrl(baseUrl, authPath);

        URI authUri = URI.create(fullUrl);
        if (!ssrfGuardValidator.isUriPermitted(authUri)) {
            log.error("AI-ERP authentication endpoint failed SSRF validation: {}", fullUrl);
            throw new NetheriteException(SystemErrorCode.ERR_RESOURCE_NOT_BOUND,
                    "AI-ERP authentication destination failed network validation");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> loginBody = Map.of("email", email, "password", password);
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(loginBody, headers);

            log.info("Authenticating to AI-ERP at {}", authUri.getHost());
            ResponseEntity<String> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                        "AI-ERP login failed with status " + response.getStatusCode().value());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            boolean success = root.path("success").asBoolean(false) || root.path("Success").asBoolean(false);
            if (!success) {
                String errorMsg = root.path("message").asText(root.path("error").asText("Unknown ERP authentication error"));
                log.warn("AI-ERP login returned failure: {}", errorMsg);
                throw new NetheriteException(SystemErrorCode.ERR_FORBIDDEN,
                        "AI-ERP authentication rejected: " + errorMsg);
            }

            JsonNode tokenNode = root.get("object");
            if (tokenNode == null || tokenNode.isNull() || tokenNode.asText().isBlank()) {
                tokenNode = root.get("data");
            }
            if (tokenNode == null || tokenNode.isNull() || tokenNode.asText().isBlank()) {
                throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                        "AI-ERP login response did not contain an access token");
            }

            String token = tokenNode.asText();
            tokenCache.put(cacheKey, new TokenEntry(token, Instant.now()));
            log.info("AI-ERP token acquired successfully");
            return token;

        } catch (NetheriteException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to authenticate with AI-ERP: {}", e.getMessage());
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Failed to communicate with AI-ERP authentication service", e);
        }
    }

    private String tryRefreshToken(String baseUrl, String currentToken) {
        String refreshPath = "api/v3/en_US/auth/jwt/token/refresh";
        String fullUrl = normalizeUrl(baseUrl, refreshPath);

        URI refreshUri = URI.create(fullUrl);
        if (!ssrfGuardValidator.isUriPermitted(refreshUri)) {
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> refreshBody = Map.of("token", currentToken);
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(refreshBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            boolean success = root.path("success").asBoolean(false) || root.path("Success").asBoolean(false);
            if (!success) {
                return null;
            }

            JsonNode tokenNode = root.get("object");
            if (tokenNode == null || tokenNode.isNull() || tokenNode.asText().isBlank()) {
                tokenNode = root.get("data");
            }
            if (tokenNode == null || tokenNode.isNull() || tokenNode.asText().isBlank()) {
                return null;
            }

            return tokenNode.asText();
        } catch (Exception e) {
            log.debug("Token refresh attempt threw exception: {}", e.getMessage());
            return null;
        }
    }

    private String normalizeUrl(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }
}
