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
        metrics.appError("ERR_INTERNAL_SERVER_ERROR");
        log.error("Unhandled exception: {}", LogSanitizer.clean(ex.getMessage()), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "ERR_INTERNAL_SERVER_ERROR",
                "message", "An unexpected internal error occurred",
                "requestId", currentRequestId(request),
                "timestamp", Instant.now().toString()));
    }

    private static String currentRequestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationIdFilter.ATTR_REQUEST_ID);
        return attribute != null ? attribute.toString() : "-";
    }
}
