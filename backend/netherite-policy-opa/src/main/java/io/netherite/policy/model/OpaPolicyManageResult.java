package io.netherite.policy.model;

/**
 * Result of a management-plane operation against the policy engine (query,
 * push, delete or pre-flight compile). Plain value object so the SPI stays free
 * of checked exceptions and the service layer decides how to react.
 *
 * @param successful whether the engine accepted the operation (2xx)
 * @param statusCode HTTP status code returned by the engine (0 for transport errors)
 * @param message    engine-provided or client-generated detail
 */
public record OpaPolicyManageResult(boolean successful, int statusCode, String message) {

    public static OpaPolicyManageResult success(int statusCode, String message) {
        return new OpaPolicyManageResult(true, statusCode, message);
    }

    public static OpaPolicyManageResult failure(int statusCode, String message) {
        return new OpaPolicyManageResult(false, statusCode, message);
    }
}