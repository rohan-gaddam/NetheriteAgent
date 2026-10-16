package io.netherite.approval.spi;

import io.netherite.approval.model.ApprovalStatus;
import io.netherite.approval.model.ApprovalTicket;
import io.netherite.context.model.DecisionContext;

import java.util.List;
import java.util.Optional;

/**
 * SPI for managing human-in-the-loop approval requests.
 */
public interface ApprovalService {

    /**
     * Creates an asynchronous approval ticket for a policy decision requiring human authorization.
     */
    ApprovalTicket createTicket(DecisionContext context, List<String> triggeredPolicies);

    /**
     * Retrieves an approval ticket by ID.
     */
    Optional<ApprovalTicket> getTicket(String ticketId);

    /**
     * Resolves an approval ticket with human decision (APPROVED or REJECTED).
     */
    ApprovalTicket resolveTicket(String ticketId, ApprovalStatus decision, String approverUserId, String notes);

    /**
     * Resolves an approval ticket with execution result (for post-approval continuation).
     */
    ApprovalTicket resolveTicketWithResult(String ticketId, ApprovalStatus decision, String approverUserId, String notes, String executionResult, String executionError);

    /**
     * Lists all pending approval tickets for an organization.
     */
    List<ApprovalTicket> listPendingByOrganization(String organizationId);

    /**
     * Lists approval tickets for an organization filtered by status.
     */
    List<ApprovalTicket> listByOrganizationAndStatus(String organizationId, ApprovalStatus status);

    /**
     * Expires a pending approval ticket that has passed its TTL.
     */
    ApprovalTicket expireTicket(String ticketId);
}
