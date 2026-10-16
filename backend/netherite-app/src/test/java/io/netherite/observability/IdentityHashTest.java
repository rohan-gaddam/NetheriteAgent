package io.netherite.observability;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS8 — bounded, non-reversible identifier hashing for logs and metric labels.
 */
class IdentityHashTest {

    private static final Pattern SHA_PREFIX_12 = Pattern.compile("[0-9a-f]{12}");

    @Test
    void isDeterministic() {
        assertThat(IdentityHash.shortHash("user-123")).isEqualTo(IdentityHash.shortHash("user-123"));
    }

    @Test
    void emitsTwelveLowercaseHexChars() {
        assertThat(IdentityHash.shortHash("user-123")).matches(SHA_PREFIX_12.asPredicate());
    }

    @Test
    void differsAcrossValues() {
        assertThat(IdentityHash.shortHash("user-123")).isNotEqualTo(IdentityHash.shortHash("user-124"));
    }

    @Test
    void nullAndBlankMapToNa() {
        assertThat(IdentityHash.shortHash(null)).isEqualTo("na");
        assertThat(IdentityHash.shortHash("  ")).isEqualTo("na");
    }
}