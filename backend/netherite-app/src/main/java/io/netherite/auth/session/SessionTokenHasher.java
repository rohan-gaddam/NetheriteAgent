package io.netherite.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way digest for session credentials at rest.
 *
 * <p>The raw session token (the value carried in the HttpOnly cookie) is generated as a
 * 122-bit random value and is only ever handed to the browser. The database stores
 * {@code SHA-256(rawToken)} instead of the plaintext, so a read-only database compromise,
 * backup leak or misconfigured index does not yield a replayable credential. The digest is
 * not reversible and, being keyless, deliberately performs no per-boot secret mixing — the
 * lookup path hashes the presented cookie and compares digests, keeping validation exact and
 * timing-stable.</p>
 *
 * <p>Fail-closed: an environment without SHA-256 (never on a supported JDK) surfaces an
 * {@link IllegalStateException} rather than degrading to a non-hashing mode.</p>
 */
public final class SessionTokenHasher {

    private SessionTokenHasher() {
    }

    public static String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return rawToken;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest is unavailable", e);
        }
    }
}