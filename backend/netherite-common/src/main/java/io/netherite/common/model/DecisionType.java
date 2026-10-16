package io.netherite.common.model;

/**
 * Deterministic decision outcomes for an AI agent action.
 * Precedence hierarchy (locked): DENY > HUMAN_APPROVAL > ALLOW.
 */
public enum DecisionType {
    ALLOW,
    DENY,
    HUMAN_APPROVAL
}
