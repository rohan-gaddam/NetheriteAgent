package io.netherite.security.key;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Authenticated encryption for resource credentials at rest.
 *
 * <p>Uses AES-256-GCM with a randomly generated 96-bit IV per encryption operation.
 * The serialized form is {@code base64(IV || ciphertext)} where the GCM authentication
 * tag (128 bits) is appended to the ciphertext by the JDK GCM implementation.
 *
 * <p>The encryption key is supplied exclusively through external configuration
 * ({@code netherite.security.credential-encryption-key}, base64-encoded 256-bit key).
 * No key is generated, derived, or stored by this service. If the key is not
 * configured, the Spring bean is not created and any resource carrying encrypted
 * credentials fails closed at execution time.
 *
 * <p>This service is stateless and thread-safe.
 */
@Component
@ConditionalOnExpression("'${netherite.security.credential-encryption-key:}'.trim().length() > 0")
public class CredentialEncryptionService {

    private static final int KEY_BITS = 256;
    private static final int KEY_BYTES = KEY_BITS / 8;
    private static final int IV_BYTES = 12; // Recommended IV size for GCM
    private static final int TAG_BITS = 128; // GCM authentication tag
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final SecretKeySpec key;

    /**
     * Creates the encryption service from a base64-encoded 256-bit key.
     *
     * @param keyBase64 base64-encoded 256-bit AES key from external configuration
     * @throws IllegalStateException if the key is missing, malformed, or the wrong length
     */
    public CredentialEncryptionService(@Value("${netherite.security.credential-encryption-key}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException(
                    "netherite.security.credential-encryption-key must be configured to use encrypted credentials");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "netherite.security.credential-encryption-key is not valid base64", e);
        }
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "netherite.security.credential-encryption-key must decode to "
                            + KEY_BYTES + " bytes (" + KEY_BITS + " bits), got " + keyBytes.length + " bytes");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts a plaintext credential string using AES-256-GCM.
     *
     * @param plaintext the credential JSON or secret to encrypt
     * @return base64-encoded ciphertext ({@code IV || ciphertext})
     * @throws IllegalStateException if encryption fails
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(IV_BYTES + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    /**
     * Decrypts a base64-encoded AES-256-GCM ciphertext back to plaintext.
     *
     * @param ciphertextBase64 base64-encoded ciphertext produced by {@link #encrypt(String)}
     * @return the decrypted plaintext string
     * @throws IllegalStateException if the ciphertext is malformed, the key is wrong,
     *         or the authentication tag does not match (tampering detected)
     */
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) {
            throw new IllegalArgumentException("ciphertextBase64 must not be null or blank");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertextBase64);
            if (combined.length < IV_BYTES + 16) { // minimum: IV + GCM tag
                throw new IllegalArgumentException("Ciphertext too short to contain IV and tag");
            }

            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);

            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential - key mismatch or tampered ciphertext", e);
        }
    }
}
