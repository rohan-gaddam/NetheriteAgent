package io.netherite.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS8 — safe logging sanitizer.
 *
 * <p>{@link LogSanitizer} guarantees that attacker-influenceable text can never carry
 * terminal-control sequences, ANSI escapes, or line breaks into a log collector, and that
 * messages and stack traces are bounded in size.</p>
 */
class LogSanitizerTest {

    @Test
    void replacesControlCharactersWithReplacementChar() {
        assertThat(LogSanitizer.scrub("hello\u0000\u001bb[31mred")).isEqualTo("hello\uFFFD\uFFFDb[31mred");
        assertThat(LogSanitizer.scrub("ansi\u001b")).isEqualTo("ansi\uFFFD");
    }

    @Test
    void replacesLineBreaksAndDelimiter() {
        assertThat(LogSanitizer.scrub("line1\nline2\rline3\tline4"))
                .isEqualTo("line1\uFFFDline2\uFFFDline3\uFFFDline4");
        assertThat(LogSanitizer.scrub("del\u007Fchar")).isEqualTo("del\uFFFDchar");
        assertThat(LogSanitizer.scrub("para\u2028sep\u2029")).isEqualTo("para\uFFFDsep\uFFFD");
    }

    @Test
    void leavesCleanTextUntouched() {
        assertThat(LogSanitizer.scrub("plain message 123")).isEqualTo("plain message 123");
        assertThat(LogSanitizer.clean("plain message")).isEqualTo("plain message");
    }

    @Test
    void nullBecomesEmptyString() {
        assertThat(LogSanitizer.clean(null)).isEmpty();
        assertThat(LogSanitizer.scrub(null)).isEmpty();
    }

    @Test
    void truncatesToConfiguredLength() {
        String longMessage = "x".repeat(10_000);
        assertThat(LogSanitizer.clean(longMessage)).hasSize(LogSanitizer.DEFAULT_MAX_LENGTH);
        assertThat(LogSanitizer.clean(longMessage, 100)).hasSize(100);
    }

    @Test
    void stackTraceSanitizationKeepsSanitizedPayload() {
        String stack = "java.lang.IllegalStateException: boom\na\u001bb[31m\tat io.netherite.Foo.run(Foo.java:1)";
        assertThat(LogSanitizer.clean(stack, LogSanitizer.DEFAULT_MAX_STACK_LENGTH))
                .doesNotContain("\u001b", "\t", "\n")
                .contains("boom", "io.netherite.Foo.run");
    }
}