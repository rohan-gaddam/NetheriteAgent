package io.netherite.notification.service;

import io.netherite.approval.spi.NotificationChannel;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "netherite.notifications.email.enabled", havingValue = "true")
public class EmailNotificationService implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String toAddress;
    private final String controlPlaneUrl;

    public EmailNotificationService(
            JavaMailSender mailSender,
            @Value("${netherite.notifications.email.from:noreply@example.com}") String fromAddress,
            @Value("${netherite.notifications.email.to:admin@example.com}") String toAddress,
            @Value("${netherite.notifications.control-plane-url:http://localhost:3000}") String controlPlaneUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.controlPlaneUrl = controlPlaneUrl;
    }

    @Override
    public String getChannelName() {
        return "email";
    }

    @Override
    public void onApprovalCreated(String organizationId, String ticketId, String actionType,
                                  String targetResource, String agentId) {
        String subject = "NetheriteAgent \u2014 Human Approval Required: " + actionType;
        String reviewUrl = controlPlaneUrl + "/approvals";

        String htmlBody = """
            <html>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #1e293b; max-width: 600px; margin: 0 auto; padding: 20px;">
              <div style="background: #1e1b4b; color: white; padding: 20px 24px; border-radius: 8px 8px 0 0;">
                <h2 style="margin: 0; font-size: 18px;">NetheriteAgent Approval Required</h2>
              </div>
              <div style="background: #f8fafc; border: 1px solid #e2e8f0; border-top: none; padding: 24px; border-radius: 0 0 8px 8px;">
                <p style="margin: 0 0 16px; font-size: 15px;">A new agent action requires human approval before it can proceed.</p>
                <table style="width: 100%%; border-collapse: collapse; margin: 16px 0;">
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px; width: 120px;">Action</td><td style="padding: 8px 0; font-weight: 600; font-size: 14px;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Agent</td><td style="padding: 8px 0; font-size: 14px;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Resource</td><td style="padding: 8px 0; font-size: 14px;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Status</td><td style="padding: 8px 0; font-size: 14px;"><span style="background: #fef3c7; color: #92400e; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 600;">PENDING</span></td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Ticket</td><td style="padding: 8px 0; font-size: 13px; font-family: monospace; color: #475569;">%s</td></tr>
                </table>
                <div style="margin: 24px 0;">
                  <a href="%s" style="display: inline-block; background: #4f46e5; color: white; padding: 10px 24px; border-radius: 6px; text-decoration: none; font-weight: 600; font-size: 14px;">Review Approval</a>
                </div>
                <p style="margin: 16px 0 0; color: #94a3b8; font-size: 12px;">This notification is informational only. Approval must be granted through the authenticated NetheriteAgent Control Plane.</p>
              </div>
            </body>
            </html>
            """.formatted(actionType, agentId, targetResource, ticketId, reviewUrl);

        sendEmail(subject, htmlBody);
    }

    @Override
    public void onApprovalResolved(String organizationId, String ticketId, String decision,
                                   String actionType, String targetResource) {
        String subject = "NetheriteAgent \u2014 Approval " + decision + ": " + actionType;
        String color = "APPROVED".equals(decision) ? "#16a34a" : "#dc2626";
        String badge = "APPROVED".equals(decision) ? "APPROVED" : "REJECTED";

        String htmlBody = """
            <html>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #1e293b; max-width: 600px; margin: 0 auto; padding: 20px;">
              <div style="background: #1e1b4b; color: white; padding: 20px 24px; border-radius: 8px 8px 0 0;">
                <h2 style="margin: 0; font-size: 18px;">NetheriteAgent Approval %s</h2>
              </div>
              <div style="background: #f8fafc; border: 1px solid #e2e8f0; border-top: none; padding: 24px; border-radius: 0 0 8px 8px;">
                <p style="margin: 0 0 16px; font-size: 15px;">An approval request has been <strong>%s</strong>.</p>
                <table style="width: 100%%; border-collapse: collapse; margin: 16px 0;">
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px; width: 120px;">Action</td><td style="padding: 8px 0; font-weight: 600; font-size: 14px;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Resource</td><td style="padding: 8px 0; font-size: 14px;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Decision</td><td style="padding: 8px 0; font-size: 14px;"><span style="background: %s15; color: %s; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 600;">%s</span></td></tr>
                  <tr><td style="padding: 8px 0; color: #64748b; font-size: 13px;">Ticket</td><td style="padding: 8px 0; font-size: 13px; font-family: monospace; color: #475569;">%s</td></tr>
                </table>
              </div>
            </body>
            </html>
            """.formatted(badge, decision.toLowerCase(), actionType, targetResource, color, color, badge, ticketId);

        sendEmail(subject, htmlBody);
    }

    private void sendEmail(String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toAddress);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent: subject='{}', to='{}'", subject, toAddress);
        } catch (MessagingException e) {
            log.error("Failed to send email: {}", e.getMessage());
            throw new RuntimeException("Email delivery failed: " + e.getMessage(), e);
        }
    }
}
