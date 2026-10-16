package io.netherite.approval.model;

import io.netherite.common.model.CanonicalAction;
import io.netherite.context.model.DecisionContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Human approval request ticket entity representation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTicket {
    private String ticketId;
    private String organizationId;
    private String agentId;
    private String actionType;
    private String targetResource;
    private CanonicalAction action;
    private DecisionContext context;
    private List<String> triggeredPolicies;
    private ApprovalStatus status;
    private String approverUserId;
    private String approverNotes;
    private Instant expiresAt;
    private Instant resolvedAt;
    private Instant createdAt;
    private String executionResult;
    private String executionError;
}
