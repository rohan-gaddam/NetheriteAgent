package io.netherite.approval.spi;

/**
 * SPI for sending notifications about approval events through various channels.
 */
public interface NotificationService {

    /**
     * Called when a new approval ticket is created.
     */
    void sendApprovalCreated(String organizationId, String ticketId, String actionType,
                             String targetResource, String agentId);

    /**
     * Called when an approval ticket is resolved (approved or rejected).
     */
    void sendApprovalResolved(String organizationId, String ticketId, String decision,
                              String actionType, String targetResource);
}
