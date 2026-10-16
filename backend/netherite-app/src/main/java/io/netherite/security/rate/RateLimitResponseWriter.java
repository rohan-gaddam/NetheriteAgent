package io.netherite.security.rate;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Writes standardized 429/503 payloads for the two rate-limit filters.
 */
@Component
@RequiredArgsConstructor
public class RateLimitResponseWriter {

    private final ObjectMapper objectMapper;

    public void sendTooManyRequests(HttpServletResponse response, int retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", "ERR_RATE_LIMIT_EXCEEDED",
                "message", "Too many requests. Please retry after the specified delay.",
                "retryAfterSeconds", retryAfterSeconds,
                "timestamp", Instant.now().toString())));
    }

    public void sendDependencyUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", "ERR_SYSTEM_UNAVAILABLE",
                "message", "A required infrastructure dependency is unavailable. Please retry later.",
                "timestamp", Instant.now().toString())));
    }
}