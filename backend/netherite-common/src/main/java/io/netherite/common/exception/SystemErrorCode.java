package io.netherite.common.exception;

/**
 * Standard system error codes distinguishing infrastructure failures from policy evaluations.
 */
public enum SystemErrorCode {
    // 400 Bad Request / Normalization
    ERR_CANONICALIZATION_FAILED(400, "Action could not be normalized into a valid canonical format"),
    ERR_INVALID_PARAMETERS(400, "Action parameters violate resource schema"),
    
    // 401 Unauthorized
    ERR_AUTHENTICATION_FAILED(401, "Agent or User authentication failed"),
    ERR_AUTHENTICATION_REQUIRED(401, "Authentication is required for this operation"),
    ERR_KEY_REVOKED_OR_EXPIRED(401, "The supplied credentials have been revoked or expired"),

    // 403 Forbidden
    ERR_FORBIDDEN(403, "You are not authorized to perform this operation"),
    ERR_RESOURCE_NOT_BOUND(403, "The agent is not bound or authorized to access the requested resource"),
    ERR_KILL_SWITCH_ACTIVE(403, "Emergency control kill-switch is active for this agent/organization"),

    // 429 Too Many Requests (distributed rate limiting)
    ERR_RATE_LIMIT_EXCEEDED(429, "Request rate limit exceeded"),

    // 404 Not Found
    ERR_RESOURCE_NOT_FOUND(404, "Requested resource, agent, or entity not found"),

    // 409 Conflict (distributed execution guarantees)
    ERR_IDEMPOTENCY_CONFLICT(409, "Duplicate or conflicting execution for this idempotency key"),

    // 409 Conflict (WS6 concurrent ownership transfer)
    ERR_CONFLICT(409, "The resource changed while this request was in progress"),

    // 424 Failed Dependency
    ERR_UNCERTAIN_DECISION_CONTEXT(424, "Required business facts could not be reliably verified from enterprise systems"),

    // 422 Unprocessable Entity (policy content rejected before it reaches the engine)
    ERR_POLICY_PUSH_REJECTED(422, "Policy was rejected after pre-flight validation against the policy engine"),

    // 503 Service Unavailable (Infrastructure Outages - Never classified as policy DENY)
    ERR_SYSTEM_UNAVAILABLE(503, "A required infrastructure dependency (OPA, Redis, DB) is unavailable"),
    ERR_POLICY_PUSH_FAILED(503, "Policy could not be synchronized to the policy engine"),
    ERR_CONNECTOR_TIMEOUT(504, "Outbound enterprise resource call timed out");

    private final int httpStatus;
    private final String defaultMessage;

    SystemErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
