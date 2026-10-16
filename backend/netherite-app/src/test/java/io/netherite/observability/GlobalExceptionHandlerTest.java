package io.netherite.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netherite.api.GlobalExceptionHandler;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS8 — standardized error bodies carry the request correlation id and bump a bounded
 * application-error counter.
 */
class GlobalExceptionHandlerTest {

    private SimpleMeterRegistry registry;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        handler = new GlobalExceptionHandler(new NetheriteMetrics(registry));
    }

    @Test
    void netheriteExceptionBodiesContainRequestIdAndIncrementCounter() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTR_REQUEST_ID, "corr-netherite");

        Map<String, Object> body = handler.handleNetheriteException(
                new NetheriteException(SystemErrorCode.ERR_FORBIDDEN, "denied"), request).getBody();

        assertThat(body.get("requestId")).isEqualTo("corr-netherite");
        assertThat(handler.handleNetheriteException(
                new NetheriteException(SystemErrorCode.ERR_FORBIDDEN, "denied"), request)
                .getStatusCode().value()).isEqualTo(SystemErrorCode.ERR_FORBIDDEN.getHttpStatus());
        assertThat(registry.get("netherite.app.errors_total").tag("code", "ERR_FORBIDDEN").counter()
                .count()).isEqualTo(2);
    }

    @Test
    void validationErrorBodiesCarryRequestIdAndCounter() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTR_REQUEST_ID, "corr-validation");

        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "name", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);

        Map<String, Object> body = handler.handleValidationException(ex, request).getBody();

        assertThat(body.get("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("requestId")).isEqualTo("corr-validation");
        assertThat(registry.get("netherite.app.errors_total").tag("code", "VALIDATION_FAILED").counter()
                .count()).isEqualTo(1);
    }

    @Test
    void notFoundBodiesCarryRequestIdWithoutCounter() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTR_REQUEST_ID, "corr-404");

        Map<String, Object> body = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/nope"), request).getBody();

        assertThat(body.get("error")).isEqualTo("ERR_NOT_FOUND");
        assertThat(body.get("requestId")).isEqualTo("corr-404");
        assertThat(registry.find("netherite.app.errors_total").meters()).isEmpty();
    }

    @Test
    void missingCorrelationAttributeFallsBackToDash() {
        var request = new MockHttpServletRequest();

        Map<String, Object> body = handler.handleNetheriteException(
                new NetheriteException(SystemErrorCode.ERR_FORBIDDEN, "denied"), request).getBody();

        assertThat(body.get("requestId")).isEqualTo("-");
    }
}