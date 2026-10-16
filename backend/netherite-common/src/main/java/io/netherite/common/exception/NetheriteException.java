package io.netherite.common.exception;

import lombok.Getter;

/**
 * Base runtime exception for NetheriteAgent control plane.
 */
@Getter
public class NetheriteException extends RuntimeException {

    private final SystemErrorCode errorCode;
    private final String details;

    public NetheriteException(SystemErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = errorCode.getDefaultMessage();
    }

    public NetheriteException(SystemErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.details = customMessage;
    }

    public NetheriteException(SystemErrorCode errorCode, String customMessage, Throwable cause) {
        super(customMessage, cause);
        this.errorCode = errorCode;
        this.details = customMessage;
    }
}
