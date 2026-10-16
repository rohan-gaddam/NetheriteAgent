package io.netherite.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;
import java.util.Map;

/**
 * Single-line JSON log layout for the production (structured) profile.
 *
 * <p>WS8 safe structured logging without a new logging-library dependency: a small hand-rolled
 * layout emits one JSON object per physical line. Values are sanitized and escaped so
 * attacker-influenceable content cannot break the line or inject control sequences, and
 * messages/stack traces are bounded. The layout never serializes request bodies or headers, so
 * credentials and session cookies cannot leak through the console stream by accident.</p>
 *
 * <p>Fields: {@code ts}, {@code level}, {@code logger}, {@code thread}, {@code service},
 * {@code environment}, {@code requestId} (from MDC, set by {@link CorrelationIdFilter}) and,
 * when present, {@code event} (a coarse classification such as {@code security.auth_failure})
 * set on the MDC at the log site. The {@code message} and optional {@code exception} fields
 * hold the sanitized payload.</p>
 */
public class SafeJsonLayout extends LayoutBase<ILoggingEvent> {

    private static final String SERVICE_NAME = "netherite-agent-control-plane";
    private static final String DEFAULT_REQUEST_ID = "-";
    private static final String ENVIRONMENT = environment();

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String requestId = mdc != null ? mdc.get(CorrelationIdFilter.MDC_REQUEST_ID) : null;
        String eventClass = mdc != null ? mdc.get(CorrelationIdFilter.MDC_EVENT) : null;

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "ts", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
        field(sb, "level", event.getLevel().toString(), true);
        field(sb, "logger", event.getLoggerName(), true);
        field(sb, "thread", event.getThreadName(), true);
        field(sb, "service", SERVICE_NAME, true);
        field(sb, "environment", ENVIRONMENT, true);
        field(sb, "requestId", requestId == null || requestId.isBlank() ? DEFAULT_REQUEST_ID : requestId, true);
        if (eventClass != null && !eventClass.isBlank()) {
            field(sb, "event", eventClass, true);
        }
        field(sb, "message", LogSanitizer.clean(event.getFormattedMessage()), true);
        if (event.getThrowableProxy() != null) {
            field(sb, "exception",
                    LogSanitizer.clean(ThrowableProxyUtil.asString(event.getThrowableProxy()),
                            LogSanitizer.DEFAULT_MAX_STACK_LENGTH),
                    true);
        }
        sb.setLength(sb.length() - 1);
        sb.append("}\n");
        return sb.toString();
    }

    private void field(StringBuilder sb, String name, String value, boolean quote) {
        sb.append('"').append(name).append('"').append(':');
        if (quote) {
            sb.append('"').append(escape(value)).append('"');
        } else {
            sb.append(value);
        }
        sb.append(',');
    }

    /**
     * JSON string escaping: backslash, double quote and every control character (including
     * tab/newline) become escape sequences, so the emitted line is always one valid JSON document.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String escaped = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> c < 0x20 || c == 0x7F
                        ? "\\u" + String.format("%04x", (int) c)
                        : null;
            };
            if (escaped != null) {
                if (sb == null) {
                    sb = new StringBuilder(value.length() + 16).append(value, 0, i);
                }
                sb.append(escaped);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? value : sb.toString();
    }

    private static String environment() {
        String env = System.getenv("NETHERITE_ENV");
        if (env == null || env.isBlank()) {
            // Consistent with application.yml default.
            return "development";
        }
        return env;
    }
}