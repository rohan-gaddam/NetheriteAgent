package io.netherite.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS8 — structured security event emission.
 *
 * <p>{@link SecurityEvents} marks the MDC {@code event} key around a log call so the JSON
 * layout can surface a stable classification, and must always clear it afterwards so
 * ordinary request logging stays uncorrupted.</p>
 */
class SecurityEventsTest {

    private final AtomicReference<Map<String, String>> mdcAtAppend = new AtomicReference<>();

    private Logger capturingLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.netherite.observability.security-events");
        logger.detachAndStopAllAppenders();
        logger.addAppender(new AppenderBase<ILoggingEvent>() {
            {
                setContext(logger.getLoggerContext());
                start();
            }

            @Override
            protected void append(ILoggingEvent event) {
                // Snapshot the thread MDC at the moment the event is actually emitted,
                // which is the contract the JSON layout relies on: the classification
                // key must be present while the log statement executes.
                Map<String, String> copy = MDC.getCopyOfContextMap();
                mdcAtAppend.set(copy == null ? Map.of() : new HashMap<>(copy));
            }
        });
        logger.setAdditive(false);
        return logger;
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void warnMarksEventInTheCapturedRecordAndClearsAfterwards() {
        Logger logger = capturingLogger();
        MDC.put(CorrelationIdFilter.MDC_REQUEST_ID, "req-1");

        SecurityEvents.warn(logger, "security.auth_failure", "rejected reason={}", "bad-session");

        assertThat(mdcAtAppend.get())
                .containsEntry("event", "security.auth_failure")
                .containsEntry("requestId", "req-1");
        assertThat(MDC.get(CorrelationIdFilter.MDC_EVENT)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID)).isEqualTo("req-1");
    }

    @Test
    void errorMarksEventAndClearsAfterwards() {
        Logger logger = capturingLogger();

        SecurityEvents.error(logger, "security.authorization_denied", "denied surface={}", "actions");

        assertThat(mdcAtAppend.get()).containsEntry("event", "security.authorization_denied");
        assertThat(MDC.get(CorrelationIdFilter.MDC_EVENT)).isNull();
    }
}