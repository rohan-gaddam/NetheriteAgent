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
@ConditionalOnProperty(name = "netherite.notifications.slack.enabled", havingValue = "true")
public class SlackNotificationService implements NotificationChannel {

    private final String webhookUrl;
    private final String controlPlaneUrl;
    private final RestTemplate restTemplate;
    private final SsrfGuardValidator ssrfGuardValidator;

    @Autowired
    public SlackNotificationService(
            @Value("${netherite.notifications.slack.webhook-url}") String webhookUrl,
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

    SlackNotificationService(String webhookUrl, String controlPlaneUrl, RestTemplate restTemplate) {
        this.webhookUrl = webhookUrl;
        this.controlPlaneUrl = controlPlaneUrl;
        this.restTemplate = restTemplate;
        this.ssrfGuardValidator = null;
    }

    @Override
    public String getChannelName() {
        return "slack";
    }

    @Override
    public void onApprovalCreated(String organizationId, String ticketId, String actionType,
                                  String targetResource, String agentId) {
        String reviewUrl = controlPlaneUrl + "/approvals";

        Map<String, Object> payload = Map.of(
                "text", ":hourglass_flowing_sand: *New Approval Required*",
                "blocks", List.of(
                        Map.of("type", "section", "text", Map.of("type", "mrkdwn",
                                "text", ":hourglass_flowing_sand: *New Approval Required*\nA new agent action requires human review.")),
                        Map.of("type", "section", "fields", List.of(
                                Map.of("type", "mrkdwn", "text", "*Action:*\n" + actionType),
                                Map.of("type", "mrkdwn", "text", "*Resource:*\n" + targetResource),
                                Map.of("type", "mrkdwn", "text", "*Agent:*\n" + agentId),
                                Map.of("type", "mrkdwn", "text", "*Status:*\n:large_orange_circle: PENDING")
                        )),
                        Map.of("type", "section", "text", Map.of("type", "mrkdwn",
                                "text", "`" + ticketId + "`")),
                        Map.of("type", "actions", "elements", List.of(
                                Map.of("type", "button", "text", Map.of("type", "plain_text", "text", "Review Approval"),
                                        "url", reviewUrl, "style", "primary")
                        ))
                )
        );
        sendSlackMessage(payload);
    }

    @Override
    public void onApprovalResolved(String organizationId, String ticketId, String decision,
                                   String actionType, String targetResource) {
        String emoji = "APPROVED".equals(decision) ? ":white_check_mark:" : ":x:";
        String color = "APPROVED".equals(decision) ? "approved" : "rejected";

        Map<String, Object> payload = Map.of(
                "text", emoji + " Approval " + decision + " for " + actionType,
                "blocks", List.of(
                        Map.of("type", "section", "text", Map.of("type", "mrkdwn",
                                "text", emoji + " *Approval " + decision + "*")),
                        Map.of("type", "section", "fields", List.of(
                                Map.of("type", "mrkdwn", "text", "*Action:*\n" + actionType),
                                Map.of("type", "mrkdwn", "text", "*Resource:*\n" + targetResource),
                                Map.of("type", "mrkdwn", "text", "*Decision:*\n" + decision),
                                Map.of("type", "mrkdwn", "text", "*Ticket:*\n`" + ticketId + "`")
                        ))
                )
        );
        sendSlackMessage(payload);
    }

    private void sendSlackMessage(Map<String, Object> payload) {
        if (!isWebhookPermitted()) {
            log.warn("Slack webhook blocked by SSRF guard: {}", webhookUrl);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Slack webhook returned non-2xx status={}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Slack delivery failed: {}", e.getMessage());
            throw new RuntimeException("Slack delivery failed: " + e.getMessage(), e);
        }
    }

    private boolean isWebhookPermitted() {
        if (ssrfGuardValidator == null) {
            return true;
        }
        try {
            return ssrfGuardValidator.isUriPermittedForOperatorConfigured(URI.create(webhookUrl));
        } catch (Exception e) {
            log.error("Slack webhook URL invalid: {}", webhookUrl, e);
            return false;
        }
    }
}
