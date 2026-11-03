package io.netherite.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netherite.observability.NetheriteMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for SSE client-disconnect log handling.
 *
 * <p>Emitter absolute-timeout expiry and writes to already-gone clients are
 * expected transport artifacts. They must stay out of the ERROR stream (debug,
 * no stack) while the 500 response shape is preserved; genuine failures must
 * still log at ERROR with their stack.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private NetheriteMetrics metrics;

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(metrics);
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        previousLevel = logger.getLevel();
        // Default test logging is INFO; DEBUG assertions need the level lowered.
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("async timeout expiry logs at debug without stack, response preserved")
    void asyncTimeoutIsDebug() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(new AsyncRequestTimeoutException(), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getThrowableProxy() == null
                        && e.getFormattedMessage().contains("disconnected")));
        assertFalse(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
    }

    @Test
    @DisplayName("write to disconnected client logs at debug without stack")
    void disconnectedClientIsDebug() {
        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(
                new IllegalStateException("Response not usable after response errors"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                && e.getThrowableProxy() == null));
        assertFalse(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
    }

    @Test
    @DisplayName("broken pipe cause chain logs at debug")
    void brokenPipeCauseIsDebug() {
        IOException root = new IOException("Broken pipe");
        RuntimeException wrapper = new RuntimeException("write failed", root);

        handler.handleGenericException(wrapper, request);

        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG));
        assertFalse(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
    }

    @Test
    @DisplayName("genuine failures still log at error with stack")
    void genuineFailureStaysError() {
        IllegalStateException genuine = new IllegalStateException("boom");

        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(genuine, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getThrowableProxy() != null));
    }
}
