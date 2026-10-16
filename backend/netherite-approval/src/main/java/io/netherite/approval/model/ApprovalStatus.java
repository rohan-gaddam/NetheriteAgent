package io.netherite.approval.model;

/**
 * Lifecycle states of an asynchronous human approval request.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED
}
