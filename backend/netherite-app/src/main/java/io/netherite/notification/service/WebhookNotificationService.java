package io.netherite.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.approval.spi.NotificationChannel;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
@ConditionalOnProperty(name = "netherite.notifications.webhook.enabled", havingValue = "true")
public class WebhookNotificationService implements NotificationChannel {

    private final String webhookUrl;
    private final String secret;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final int maxRetries;
    private final SsrfGuardValidator ssrfGuardValidator;

    @Autowired
    public WebhookNotificationService(
            @Value("${netherite.notifications.webhook.url}") String webhookUrl,
            @Value("${netherite.notifications.webhook.secret:}") String secret,
            @Value("${netherite.notifications.webhook.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${netherite.notifications.webhook.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${netherite.notifications.webhook.max-retries:3}") int maxRetries,
            SsrfGuardValidator ssrfGuardValidator) {
        this.webhookUrl = webhookUrl;
        this.secret = secret;
        this.maxRetries = maxRetries;
        this.objectMapper = new ObjectMapper();
        this.ssrfGuardValidator = ssrfGuardValidator;

        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    WebhookNotificationService(String webhookUrl, String secret, RestTemplate restTemplate, int maxRetries) {
        this.webhookUrl = webhookUrl;
        this.secret = secret;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
        this.maxRetries = maxRetries;
        this.ssrfGuardValidator = null;
    }

    @Override
    public String getChannelName() {
        return "webhook";
    }

    @Override
    public void onApprovalCreated(String organizationId, String ticketId, String actionType,
                                  String targetResource, String agentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "APPROVAL_CREATED");
        payload.put("ticketId", ticketId);
        payload.put("actionType", actionType);
        payload.put("targetResource", targetResource);
        payload.put("status", "PENDING");
        payload.put("organizationId", organizationId);
        payload.put("agentId", agentId);
        payload.put("createdAt", Instant.now().toString());
        sendWithRetry(payload);
    }

    @Override
    public void onApprovalResolved(String organizationId, String ticketId, String decision,
                                   String actionType, String targetResource) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "APPROVAL_RESOLVED");
        payload.put("ticketId", ticketId);
        payload.put("actionType", actionType);
        payload.put("targetResource", targetResource);
        payload.put("status", decision);
        payload.put("organizationId", organizationId);
        payload.put("resolvedAt", Instant.now().toString());
        sendWithRetry(payload);
    }

    private void sendWithRetry(Map<String, Object> payload) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                sendWebhook(payload);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("Webhook delivery attempt {}/{} failed: {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("Webhook delivery failed after {} attempts: {}", maxRetries,
                lastException != null ? lastException.getMessage() : "unknown error");
    }

    private void sendWebhook(Map<String, Object> payload) {
        if (!isWebhookPermitted()) {
            log.warn("Webhook blocked by SSRF guard: {}", webhookUrl);
            return;
        }
        try {
            String timestamp = Instant.now().toString();
            String body = objectMapper.writeValueAsString(payload);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Webhook-Timestamp", timestamp);

            if (secret != null && !secret.isBlank()) {
                String signature = computeHmacSha256(secret, timestamp + "." + body);
                headers.set("X-Webhook-Signature", "sha256=" + signature);
            }

            HttpEntity<String> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Webhook returned non-2xx status: " + response.getStatusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Webhook delivery failed: " + e.getMessage(), e);
        }
    }

    private boolean isWebhookPermitted() {
        if (ssrfGuardValidator == null) {
            return true;
        }
        try {
            return ssrfGuardValidator.isUriPermittedForOperatorConfigured(URI.create(webhookUrl));
        } catch (Exception e) {
            log.error("Webhook URL invalid: {}", webhookUrl, e);
            return false;
        }
    }

    private String computeHmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
}
