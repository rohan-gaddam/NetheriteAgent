package io.netherite.approval.spi;

/**
 * SPI for individual notification channels (email, Slack, Teams, webhook, etc.).
 * Implementations must never throw exceptions that would disrupt the approval flow.
 */
public interface NotificationChannel {

    /**
     * Returns the human-readable name of this channel (e.g., "email", "slack").
     */
    String getChannelName();

    /**
     * Called when a new approval ticket is created.
     */
    void onApprovalCreated(String organizationId, String ticketId, String actionType,
                           String targetResource, String agentId);

    /**
     * Called when an approval ticket is resolved.
     */
    void onApprovalResolved(String organizationId, String ticketId, String decision,
                            String actionType, String targetResource);
}
