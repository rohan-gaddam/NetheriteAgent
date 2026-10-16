package io.netherite.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Bounded, non-reversible identifier hashing for logs and metric labels.
 *
 * <p>WS8 privacy minimization: raw user and organization identifiers never appear in
 * structured logs or metric tags. {@code shortHash} emits the first six bytes of the
 * SHA-256 digest (12 hex characters), which is enough to join events for the same
 * identity during an incident while remaining opaque to an observer of the logs alone.</p>
 *
 * <p>This is not a security control; the identifiers are already non-secret UUIDs. It is a
 * minimization control so a log/metrics sink compromise cannot be used to enumerate the
 * full identifier space.</p>
 */
public final class IdentityHash {

    private static final int HASH_BYTES = 6;
    private static final String UNKNOWN = "na";

    private IdentityHash() {
    }

    /**
     * Returns a 12-hex SHA-256 prefix of the value, or {@code "na"} for null/blank input.
     */
    public static String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, HASH_BYTES);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCE. A runtime absence leaves the platform
            // unusable for both hashing and the audit chain; fail observable, not silent.
            throw new IllegalStateException("SHA-256 message digest is unavailable", e);
        }
    }
}