package io.netherite.security.key;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for generating cryptographically secure API keys and hashing secrets.
 * Public Key Identifier (ntk_live_...) + High-Entropy Secret -> Salted Hash stored in DB.
 */
public class ApiKeyHashService {

    private static final String KEY_PREFIX = "ntk_live_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public record GeneratedApiKey(String keyIdentifier, String secretToken, String hashedSecret, String fullApiKey) {}

    /**
     * Generates a new API key pair: a public key identifier and a high-entropy secret.
     */
    public GeneratedApiKey generateApiKey() {
        byte[] idBytes = new byte[12];
        SECURE_RANDOM.nextBytes(idBytes);
        String keyIdentifier = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);

        byte[] secretBytes = new byte[32];
        SECURE_RANDOM.nextBytes(secretBytes);
        String secretToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        String fullApiKey = keyIdentifier + "." + secretToken;
        String hashedSecret = passwordEncoder.encode(secretToken);

        return new GeneratedApiKey(keyIdentifier, secretToken, hashedSecret, fullApiKey);
    }

    /**
     * Hashes a raw secret.
     */
    public String hashSecret(String rawSecret) {
        return passwordEncoder.encode(rawSecret);
    }

    /**
     * Verifies a raw secret against a stored hash in constant time.
     */
    public boolean verifySecret(String rawSecret, String storedHash) {
        if (rawSecret == null || storedHash == null) {
            return false;
        }
        return passwordEncoder.matches(rawSecret, storedHash);
    }
}
