package io.netherite.api;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.observability.CorrelationIdFilter;
import io.netherite.observability.LogSanitizer;
import io.netherite.observability.NetheriteMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler preventing stack trace and internal detail leaks to API clients.
 * All exceptions return standardized error responses with only safe, generic information.
 * WS8: every error body carries the request correlation id, and each standardized error
 * bumps a bounded application-error counter.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final NetheriteMetrics metrics;

    @ExceptionHandler(NetheriteException.class)
    public ResponseEntity<Map<String, Object>> handleNetheriteException(
            NetheriteException ex, HttpServletRequest request) {
        String code = ex.getErrorCode().name();
        metrics.appError(code);
        log.warn("NetheriteException: code={}, details={}",
                code, LogSanitizer.clean(String.valueOf(ex.getDetails())));
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(Map.of(
                "error", code,
                "message", ex.getErrorCode().getDefaultMessage(),
                "requestId", currentRequestId(request),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        metrics.appError("VALIDATION_FAILED");
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "VALIDATION_FAILED",
                "message", "Request validation failed",
                "details", errors,
                "requestId", currentRequestId(request),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        metrics.appError("ERR_INVALID_PARAMETERS");
        log.warn("IllegalArgumentException: {}", LogSanitizer.clean(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "ERR_INVALID_PARAMETERS",
                "message", "The request contains invalid parameters",
                "requestId", currentRequestId(request),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("No resource found: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "ERR_NOT_FOUND",
                "message", "The requested resource does not exist",
                "requestId", currentRequestId(request),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, HttpServletRequest request) {
        if (isExpectedClientDisconnect(ex)) {
            // Expected transport artifact, not an application failure: the SSE
            // absolute-timeout expiry (AsyncRequestTimeoutException) and writes to
            // an already-gone client (Tomcat disconnect reports). The client is
            // gone so the body is discarded by the container; logging at debug
            // without a stack keeps routine disconnects out of the error stream.
            // Response shape and metrics are intentionally unchanged.
            log.debug("Client disconnected before response completed: {}",
                    LogSanitizer.clean(String.valueOf(ex.getMessage())));
        } else {
            log.error("Unhandled exception: {}", LogSanitizer.clean(ex.getMessage()), ex);
        }
        metrics.appError("ERR_INTERNAL_SERVER_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "ERR_INTERNAL_SERVER_ERROR",
                "message", "An unexpected internal error occurred",
                "requestId", currentRequestId(request),
                "timestamp", Instant.now().toString()));
    }

    /**
     * Confirmed expected-disconnect signals. Matched by class name (never by
     * importing container internals) and by Tomcat's stable disconnect message
     * fragments, walking the cause chain. Anything else remains an ERROR.
     */
    static boolean isExpectedClientDisconnect(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String className = current.getClass().getName();
            if (className.endsWith("AsyncRequestTimeoutException")
                    || className.contains("ClientAbortException")) {
                return true;
            }
            String message = String.valueOf(current.getMessage());
            if (message.contains("disconnected client")
                    || message.contains("Response not usable after response errors")
                    || message.contains("Broken pipe")
                    || message.contains("Connection reset")) {
                return true;
            }
        }
        return false;
    }

    private static String currentRequestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationIdFilter.ATTR_REQUEST_ID);
        return attribute != null ? attribute.toString() : "-";
    }
}
