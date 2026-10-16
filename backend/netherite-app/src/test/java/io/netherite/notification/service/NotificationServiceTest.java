package io.netherite.notification.service;

import io.netherite.approval.spi.NotificationChannel;
import io.netherite.approval.spi.NotificationService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Notification Service Tests")
class NotificationServiceTest {

    @Nested
    @DisplayName("CompositeNotificationService")
    class CompositeNotificationServiceTest {

        @Test
        @DisplayName("Routes to all registered channels")
        void routesToAllChannels() {
            NotificationChannel ch1 = mock(NotificationChannel.class);
            NotificationChannel ch2 = mock(NotificationChannel.class);

            NotificationService service = new CompositeNotificationService(List.of(ch1, ch2));
            service.sendApprovalCreated("org1", "ticket1", "WRITE", "resource1", "agent1");

            verify(ch1).onApprovalCreated("org1", "ticket1", "WRITE", "resource1", "agent1");
            verify(ch2).onApprovalCreated("org1", "ticket1", "WRITE", "resource1", "agent1");
        }

        @Test
        @DisplayName("Continues when one channel throws exception")
        void continuesOnChannelFailure() {
            NotificationChannel failing = mock(NotificationChannel.class);
            doThrow(new RuntimeException("channel down")).when(failing)
                    .onApprovalCreated(anyString(), anyString(), anyString(), anyString(), anyString());

            NotificationChannel succeeding = mock(NotificationChannel.class);

            NotificationService service = new CompositeNotificationService(List.of(failing, succeeding));
            assertDoesNotThrow(() ->
                    service.sendApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1"));

            verify(succeeding).onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1");
        }

        @Test
        @DisplayName("Routes resolved notifications to all channels")
        void routesResolvedNotifications() {
            NotificationChannel ch1 = mock(NotificationChannel.class);
            NotificationService service = new CompositeNotificationService(List.of(ch1));

            service.sendApprovalResolved("org1", "ticket1", "APPROVED", "WRITE", "res1");
            verify(ch1).onApprovalResolved("org1", "ticket1", "APPROVED", "WRITE", "res1");
        }

        @Test
        @DisplayName("No channels registered is a no-op")
        void emptyChannelsIsNoop() {
            NotificationService service = new CompositeNotificationService(List.of());
            assertDoesNotThrow(() ->
                    service.sendApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1"));
            assertDoesNotThrow(() ->
                    service.sendApprovalResolved("org1", "ticket1", "APPROVED", "WRITE", "res1"));
        }
    }

    @Nested
    @DisplayName("EmailNotificationService")
    class EmailNotificationServiceTest {

        @Mock
        JavaMailSender mailSender;

        @Test
        @DisplayName("Sends approval created email via JavaMailSender")
        void sendsApprovalCreatedEmail() throws Exception {
            MimeMessage mimeMessage = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

            EmailNotificationService service = new EmailNotificationService(
                    mailSender, "from@test.com", "to@test.com", "http://localhost:3000");
            service.onApprovalCreated("org1", "ticket123", "WRITE", "resource1", "agent1");

            verify(mailSender).send(same(mimeMessage));
        }

        @Test
        @DisplayName("Sends approval resolved email via JavaMailSender")
        void sendsApprovalResolvedEmail() throws Exception {
            MimeMessage mimeMessage = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

            EmailNotificationService service = new EmailNotificationService(
                    mailSender, "from@test.com", "to@test.com", "http://localhost:3000");
            service.onApprovalResolved("org1", "ticket123", "APPROVED", "WRITE", "resource1");

            verify(mailSender).send(same(mimeMessage));
        }

        @Test
        @DisplayName("Throws RuntimeException on SMTP failure")
        void throwsOnSmtpFailure() throws Exception {
            MimeMessage mimeMessage = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            doThrow(new org.springframework.mail.MailSendException("SMTP down"))
                    .when(mailSender).send(any(MimeMessage.class));

            EmailNotificationService service = new EmailNotificationService(
                    mailSender, "from@test.com", "to@test.com", "http://localhost:3000");

            assertThrows(RuntimeException.class, () ->
                    service.onApprovalCreated("org1", "t1", "WRITE", "res1", "a1"));
        }
    }

    @Nested
    @DisplayName("WebhookNotificationService")
    class WebhookNotificationServiceTest {

        @Mock
        RestTemplate restTemplate;

        @Test
        @DisplayName("Sends HMAC signature in header when secret configured")
        void sendsHmacSignature() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            WebhookNotificationService service = new WebhookNotificationService(
                    "https://webhook.test.com/hook", "my-secret", restTemplate, 3);

            service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(eq("https://webhook.test.com/hook"), captor.capture(), eq(String.class));

            HttpEntity<String> sent = captor.getValue();
            assertTrue(sent.getHeaders().containsKey("X-Webhook-Signature"));
            assertTrue(sent.getHeaders().getFirst("X-Webhook-Signature").startsWith("sha256="));
            assertTrue(sent.getHeaders().containsKey("X-Webhook-Timestamp"));
        }

        @Test
        @DisplayName("Retries on failure up to max-retries")
        void retriesOnFailure() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("connection refused"))
                    .thenThrow(new RuntimeException("still down"))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            WebhookNotificationService service = new WebhookNotificationService(
                    "https://webhook.test.com/hook", "", restTemplate, 3);

            service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1");

            verify(restTemplate, times(3)).postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("Gives up after max-retries exceeded")
        void givesUpAfterMaxRetries() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("connection refused"));

            WebhookNotificationService service = new WebhookNotificationService(
                    "https://webhook.test.com/hook", "", restTemplate, 2);

            assertDoesNotThrow(() ->
                    service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1"));

            verify(restTemplate, times(2)).postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("No HMAC header when secret is blank")
        void noHmacWhenNoSecret() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            WebhookNotificationService service = new WebhookNotificationService(
                    "https://webhook.test.com/hook", "", restTemplate, 1);

            service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));

            HttpEntity<String> sent = captor.getValue();
            assertFalse(sent.getHeaders().containsKey("X-Webhook-Signature"));
        }

        @Test
        @DisplayName("Sends approval resolved webhook event")
        void sendsResolvedEvent() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            WebhookNotificationService service = new WebhookNotificationService(
                    "https://webhook.test.com/hook", "", restTemplate, 1);

            service.onApprovalResolved("org1", "ticket1", "APPROVED", "WRITE", "res1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(eq("https://webhook.test.com/hook"), captor.capture(), eq(String.class));

            String body = captor.getValue().getBody();
            assertNotNull(body);
            assertTrue(body.contains("APPROVAL_RESOLVED"));
            assertTrue(body.contains("APPROVED"));
        }
    }

    @Nested
    @DisplayName("SlackNotificationService")
    class SlackNotificationServiceTest {

        @Mock
        RestTemplate restTemplate;

        @Test
        @DisplayName("Sends Slack message with review URL")
        void sendsSlackPayload() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            SlackNotificationService service = new SlackNotificationService(
                    "https://hooks.slack.com/test", "http://localhost:3000", restTemplate);
            service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(eq("https://hooks.slack.com/test"), captor.capture(), eq(String.class));

            HttpEntity<?> sent = captor.getValue();
            assertNotNull(sent.getBody());
        }

        @Test
        @DisplayName("Throws on Slack delivery failure")
        void throwsOnFailure() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Slack API error"));

            SlackNotificationService service = new SlackNotificationService(
                    "https://hooks.slack.com/test", "http://localhost:3000", restTemplate);

            assertThrows(RuntimeException.class, () ->
                    service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1"));
        }

        @Test
        @DisplayName("Sends approval resolved to Slack")
        void sendsResolvedPayload() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            SlackNotificationService service = new SlackNotificationService(
                    "https://hooks.slack.com/test", "http://localhost:3000", restTemplate);
            service.onApprovalResolved("org1", "ticket1", "APPROVED", "WRITE", "res1");

            verify(restTemplate).postForEntity(eq("https://hooks.slack.com/test"), any(HttpEntity.class), eq(String.class));
        }
    }

    @Nested
    @DisplayName("TeamsNotificationService")
    class TeamsNotificationServiceTest {

        @Mock
        RestTemplate restTemplate;

        @Test
        @DisplayName("Sends Teams message with review URL")
        void sendsTeamsPayload() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            TeamsNotificationService service = new TeamsNotificationService(
                    "https://outlook.office.com/webhook/test", "http://localhost:3000", restTemplate);
            service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(
                    eq("https://outlook.office.com/webhook/test"), captor.capture(), eq(String.class));

            HttpEntity<?> sent = captor.getValue();
            assertNotNull(sent.getBody());
        }

        @Test
        @DisplayName("Throws on Teams delivery failure")
        void throwsOnFailure() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Teams API error"));

            TeamsNotificationService service = new TeamsNotificationService(
                    "https://outlook.office.com/webhook/test", "http://localhost:3000", restTemplate);

            assertThrows(RuntimeException.class, () ->
                    service.onApprovalCreated("org1", "ticket1", "WRITE", "res1", "agent1"));
        }

        @Test
        @DisplayName("Sends approval resolved to Teams")
        void sendsResolvedPayload() {
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

            TeamsNotificationService service = new TeamsNotificationService(
                    "https://outlook.office.com/webhook/test", "http://localhost:3000", restTemplate);
            service.onApprovalResolved("org1", "ticket1", "REJECTED", "DELETE", "res1");

            verify(restTemplate).postForEntity(
                    eq("https://outlook.office.com/webhook/test"), any(HttpEntity.class), eq(String.class));
        }
    }
}
