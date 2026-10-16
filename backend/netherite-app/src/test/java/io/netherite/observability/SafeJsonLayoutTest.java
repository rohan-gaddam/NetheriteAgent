package io.netherite.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS8 — single-line JSON structured log layout.
 *
 * <p>Verifies the production structured profile contract: one valid JSON object per
 * physical line, a fixed safe field set, request correlation from the MDC, sanitization of
 * attacker-influenceable message text, and bounded stack traces. Events are produced
 * through the real (slf4j-bound) logging pipeline, exactly as the layout receives them in
 * production.</p>
 */
class SafeJsonLayoutTest {

    private final AtomicReference<ILoggingEvent> captured = new AtomicReference<>();

    private final SafeJsonLayout layout = new SafeJsonLayout();

    private Logger loggingLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.netherite.observability.SafeJsonLayoutTest");
        logger.detachAndStopAllAppenders();
        logger.addAppender(new AppenderBase<ILoggingEvent>() {
            {
                setContext(logger.getLoggerContext());
                start();
            }

            @Override
            protected void append(ILoggingEvent event) {
                captured.set(event);
            }
        });
        logger.setAdditive(false);
        logger.setLevel(Level.DEBUG);
        return logger;
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void emitsOneValidJsonLineWithFixedSafeFields() {
        Logger logger = loggingLogger();
        layout.setContext(logger.getLoggerContext());
        layout.start();
        logger.warn("hello there");

        String line = layout.doLayout(captured.get());

        assertThat(line).startsWith("{").endsWith("}\n");
        assertThat(countNewlines(line)).isEqualTo(1);
        assertThat(line).contains("\"service\":\"netherite-agent-control-plane\"");
        assertThat(line).contains("\"level\":\"WARN\"");
        assertThat(line).contains("\"logger\":\"io.netherite.observability.SafeJsonLayoutTest\"");
        assertThat(line).contains("\"message\":\"hello there\"");
    }

    @Test
    void carriesRequestIdFromMdcOrDashWhenAbsent() {
        Logger logger = loggingLogger();
        layout.setContext(logger.getLoggerContext());
        layout.start();

        MDC.put(CorrelationIdFilter.MDC_REQUEST_ID, "trace-abc-123");
        logger.info("mq");
        assertThat(layout.doLayout(captured.get()))
                .contains("\"requestId\":\"trace-abc-123\"");

        MDC.clear();
        logger.info("mq2");
        assertThat(layout.doLayout(captured.get()))
                .contains("\"requestId\":\"-\"");
    }

    @Test
    void sanitizesAndEscapesMessageTextAndStaysSingleLine() {
        Logger logger = loggingLogger();
        layout.setContext(logger.getLoggerContext());
        layout.start();

        // Tab, NUL, ESC and a double quote must not break the physical line or the JSON.
        logger.error("injected\ttext\u0000\u001b[31mred \"quoted\"");
        String line = layout.doLayout(captured.get());

        assertThat(countNewlines(line)).isEqualTo(1);
        assertThat(line).doesNotContain("\u0000", "\u001b", "\t");
        assertThat(line).contains("\uFFFD");
        assertThat(line).contains("\"message\":\"injected\uFFFDtext"); // tab sanitized to U+FFFD
        assertThat(line).contains("\\\"quoted\\\"");                  // quote JSON-escaped
    }

    @Test
    void includesSanitizedBoundedStackTrace() {
        Logger logger = loggingLogger();
        layout.setContext(logger.getLoggerContext());
        layout.start();

        logger.error("failed", new IllegalStateException("boom-boom"));
        String line = layout.doLayout(captured.get());

        assertThat(countNewlines(line)).isEqualTo(1);
        assertThat(line).contains("\"exception\":");
        assertThat(line).contains("boom-boom");
        assertThat(line).contains("IllegalStateException");
        assertThat(line).doesNotContain("\nException");
    }

    @Test
    void omitsEventFieldUnlessMarked() {
        Logger logger = loggingLogger();
        layout.setContext(logger.getLoggerContext());
        layout.start();

        logger.warn("ordinary");
        assertThat(layout.doLayout(captured.get())).doesNotContain("\"event\":");

        MDC.put(CorrelationIdFilter.MDC_EVENT, "security.auth_failure");
        logger.warn("marked");
        assertThat(layout.doLayout(captured.get()))
                .contains("\"event\":\"security.auth_failure\"");
    }

    private static long countNewlines(String value) {
        return value.chars().filter(c -> c == '\n').count();
    }
}