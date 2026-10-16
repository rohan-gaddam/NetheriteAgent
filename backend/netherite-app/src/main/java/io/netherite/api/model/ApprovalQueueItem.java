package io.netherite.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalQueueItem {
    private String ticketId;
    private String organizationId;
    private String agentId;
    private String actionType;
    private String targetResource;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
}
