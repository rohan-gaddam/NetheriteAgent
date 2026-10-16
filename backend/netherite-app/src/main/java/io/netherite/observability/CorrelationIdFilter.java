package io.netherite.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request correlation boundary (WS8).
 *
 * <p>Runs ahead of every other filter ({@code @Order(-100)}) so a request identifier exists in
 * the MDC before anything logs, and is echoed back in the {@code X-Correlation-Id} response
 * header on every response — including authentication, authorization and rate-limit rejections
 * that short-circuit further down the chain.</p>
 *
 * <p>Client-supplied identifiers are validated against a conservative character set and length
 * before being adopted; anything else is replaced by a fresh UUID. A caller can therefore never
 * inject log-injection payloads or unbounded strings into the correlation field, and never
 * controls the whole identifier space seen by log aggregators (the prefix is fixed).</p>
 */
@Component
@Order(-100)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_EVENT = "event";
    public static final String ATTR_REQUEST_ID = "netherite.requestId";

    /** RFC-conservative: alphanumeric first char, then letters/digits/._-, at most 128 chars. */
    private static final Pattern VALID_REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{0,127}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String incoming = request.getHeader(HEADER_NAME);
        String requestId = incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_REQUEST_ID, requestId);
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        CorrelationIdResponseWrapper wrapped = new CorrelationIdResponseWrapper(response, requestId);

        try {
            filterChain.doFilter(request, wrapped);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_EVENT);
        }
    }

    /**
     * Tags every response with the correlation identifier the moment it is created, so
     * short-circuiting filters cannot bypass the header.
     */
    private static final class CorrelationIdResponseWrapper extends HttpServletResponseWrapper {

        CorrelationIdResponseWrapper(HttpServletResponse response, String requestId) {
            super(response);
            if (!response.isCommitted()) {
                response.setHeader(HEADER_NAME, requestId);
            }
        }
    }
}