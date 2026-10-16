package io.netherite.notification.service;

import io.netherite.approval.spi.NotificationChannel;
import io.netherite.approval.spi.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompositeNotificationService implements NotificationService {

    private final List<NotificationChannel> channels;

    @Override
    public void sendApprovalCreated(String organizationId, String ticketId, String actionType,
                                    String targetResource, String agentId) {
        for (NotificationChannel channel : channels) {
            try {
                channel.onApprovalCreated(organizationId, ticketId, actionType, targetResource, agentId);
            } catch (Exception e) {
                log.error("Notification failed on channel {}: {}", channel.getChannelName(), e.getMessage());
            }
        }
    }

    @Override
    public void sendApprovalResolved(String organizationId, String ticketId, String decision,
                                     String actionType, String targetResource) {
        for (NotificationChannel channel : channels) {
            try {
                channel.onApprovalResolved(organizationId, ticketId, decision, actionType, targetResource);
            } catch (Exception e) {
                log.error("Notification failed on channel {}: {}", channel.getChannelName(), e.getMessage());
            }
        }
    }
}
