package io.netherite.notification.service;

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

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@ConditionalOnProperty(name = "netherite.notifications.teams.enabled", havingValue = "true")
public class TeamsNotificationService implements NotificationChannel {

    private final String webhookUrl;
    private final String controlPlaneUrl;
    private final RestTemplate restTemplate;
    private final SsrfGuardValidator ssrfGuardValidator;

    @Autowired
    public TeamsNotificationService(
            @Value("${netherite.notifications.teams.webhook-url}") String webhookUrl,
            @Value("${netherite.notifications.control-plane-url:http://localhost:3000}") String controlPlaneUrl,
            SsrfGuardValidator ssrfGuardValidator) {
        this.webhookUrl = webhookUrl;
        this.controlPlaneUrl = controlPlaneUrl;
        this.ssrfGuardValidator = ssrfGuardValidator;

        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    TeamsNotificationService(String webhookUrl, String controlPlaneUrl, RestTemplate restTemplate) {
        this.webhookUrl = webhookUrl;
        this.controlPlaneUrl = controlPlaneUrl;
        this.restTemplate = restTemplate;
        this.ssrfGuardValidator = null;
    }

    @Override
    public String getChannelName() {
        return "teams";
    }

    @Override
    public void onApprovalCreated(String organizationId, String ticketId, String actionType,
                                  String targetResource, String agentId) {
        String reviewUrl = controlPlaneUrl + "/approvals";

        Map<String, Object> payload = Map.of(
                "@type", "MessageCard",
                "@context", "http://schema.org/extensions",
                "themeColor", "FF8C00",
                "summary", "New Approval Required: " + actionType + " on " + targetResource,
                "sections", List.of(
                        Map.of(
                                "activityTitle", "New Approval Required",
                                "activitySubtitle", "A new agent action requires human review",
                                "facts", List.of(
                                        Map.of("name", "Action Type", "value", actionType),
                                        Map.of("name", "Target Resource", "value", targetResource),
                                        Map.of("name", "Agent ID", "value", agentId),
                                        Map.of("name", "Status", "value", "Pending Approval"),
                                        Map.of("name", "Ticket ID", "value", ticketId)
                                ),
                                "markdown", true
                        )
                ),
                "potentialAction", List.of(
                        Map.of(
                                "@type", "OpenUri",
                                "name", "Review Approval",
                                "targets", List.of(
                                        Map.of("os", "default", "uri", reviewUrl)
                                )
                        )
                )
        );
        sendTeamsMessage(payload);
    }

    @Override
    public void onApprovalResolved(String organizationId, String ticketId, String decision,
                                   String actionType, String targetResource) {
        String themeColor = "APPROVED".equals(decision) ? "00FF00" : "FF0000";

        Map<String, Object> payload = Map.of(
                "@type", "MessageCard",
                "@context", "http://schema.org/extensions",
                "themeColor", themeColor,
                "summary", "Approval " + decision + ": " + actionType + " on " + targetResource,
                "sections", List.of(
                        Map.of(
                                "activityTitle", "Approval " + decision,
                                "activitySubtitle", "Action: " + actionType + " on " + targetResource,
                                "facts", List.of(
                                        Map.of("name", "Decision", "value", decision),
                                        Map.of("name", "Action Type", "value", actionType),
                                        Map.of("name", "Target Resource", "value", targetResource),
                                        Map.of("name", "Ticket ID", "value", ticketId)
                                ),
                                "markdown", true
                        )
                )
        );
        sendTeamsMessage(payload);
    }

    private void sendTeamsMessage(Map<String, Object> payload) {
        if (!isWebhookPermitted()) {
            log.warn("Teams webhook blocked by SSRF guard: {}", webhookUrl);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Teams webhook returned non-2xx status={}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Teams delivery failed: {}", e.getMessage());
            throw new RuntimeException("Teams delivery failed: " + e.getMessage(), e);
        }
    }

    private boolean isWebhookPermitted() {
        if (ssrfGuardValidator == null) {
            return true;
        }
        try {
            return ssrfGuardValidator.isUriPermittedForOperatorConfigured(URI.create(webhookUrl));
        } catch (Exception e) {
            log.error("Teams webhook URL invalid: {}", webhookUrl, e);
            return false;
        }
    }
}
