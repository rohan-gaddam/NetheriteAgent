package io.netherite.observability;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Emits security event log lines with a coarse, stable MDC classification.
 *
 * <p>The {@code event} MDC key is surfaced by {@link SafeJsonLayout} as a structured field
 * (e.g. {@code security.auth_failure}, {@code security.authorization_denied}) so a log sink
 * can group security events without parsing free-text messages. The key is set only around
 * the log call and cleared immediately after, so ordinary request logging stays uncorrupted.</p>
 */
public final class SecurityEvents {

    private SecurityEvents() {
    }

    public static void warn(Logger log, String event, String format, Object... args) {
        Mark mark = new Mark(event);
        try {
            log.warn(format, args);
        } finally {
            mark.close();
        }
    }

    public static void error(Logger log, String event, String format, Object... args) {
        Mark mark = new Mark(event);
        try {
            log.error(format, args);
        } finally {
            mark.close();
        }
    }

    private static final class Mark implements AutoCloseable {

        Mark(String event) {
            MDC.put(CorrelationIdFilter.MDC_EVENT, event);
        }

        @Override
        public void close() {
            MDC.remove(CorrelationIdFilter.MDC_EVENT);
        }
    }
}