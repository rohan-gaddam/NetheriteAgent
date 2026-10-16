package io.netherite.observability;

/**
 * Sanitizes free-form text before it reaches a structured log line.
 *
 * <p>WS8 safe logging: control characters (ANSI escapes, terminal control bytes, the
 * {@code U+007F} DEL) are replaced with the Unicode replacement character so a log
 * collector cannot be fed terminal-control sequences from attacker-influenceable data.
 * Line breaks are replaced as well, guaranteeing every formatted message contributes
 * exactly one physical log line even when the logging framework does not escape it.</p>
 *
 * <p>The separate JSON escaper in {@link SafeJsonLayout} guarantees the emitted line is
 * a single valid JSON document; this class additionally keeps the text itself clean for
 * human viewers and non-JSON sinks.</p>
 */
public final class LogSanitizer {

    /** Default maximum characters kept from a message; longer messages are truncated. */
    public static final int DEFAULT_MAX_LENGTH = 1024;

    /** Default maximum characters kept from a stack trace. */
    public static final int DEFAULT_MAX_STACK_LENGTH = 4000;

    private static final char REPLACEMENT = '\uFFFD';

    private LogSanitizer() {
    }

    /**
     * Replaces control/newline characters and truncates to {@link #DEFAULT_MAX_LENGTH}.
     */
    public static String clean(String message) {
        return clean(message, DEFAULT_MAX_LENGTH);
    }

    /**
     * Replaces control/newline characters and truncates to {@code maxLength} characters.
     */
    public static String clean(String message, int maxLength) {
        if (message == null) {
            return "";
        }
        String scrubbed = scrub(message);
        return maxLength > 0 && scrubbed.length() > maxLength
                ? scrubbed.substring(0, maxLength)
                : scrubbed;
    }

    /**
     * Negates the line-injection and terminal-control risk: every ISO control character,
     * DEL and line/paragraph separator is replaced by U+FFFD.
     */
    public static String scrub(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isUnsafe(c)) {
                if (out == null) {
                    out = new StringBuilder(value.length() + 16).append(value, 0, i);
                }
                out.append(REPLACEMENT);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    private static boolean isUnsafe(char c) {
        return c < 0x20 || c == 0x7F || c == '\u2028' || c == '\u2029';
    }
}