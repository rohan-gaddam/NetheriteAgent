package io.netherite.security.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link CredentialEncryptionService} AES-256-GCM authenticated encryption.
 *
 * Covers: encrypt/decrypt round-trip, tampered ciphertext rejection, random IV per
 * operation, invalid key configuration.
 */
class CredentialEncryptionServiceTest {

    private static final String VALID_KEY_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="; // 32 bytes

    @Test
    @DisplayName("Encrypt then decrypt round-trips the plaintext")
    void encryptDecryptRoundTrip() {
        CredentialEncryptionService service = new CredentialEncryptionService(VALID_KEY_BASE64);
        String plaintext = "{\"Authorization\": \"Token erpnext_api_key:erpnext_api_secret\"}";

        String encrypted = service.encrypt(plaintext);
        String decrypted = service.decrypt(encrypted);

        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("Same plaintext produces different ciphertext (random IV)")
    void encryptProducesDifferentCiphertext() {
        CredentialEncryptionService service = new CredentialEncryptionService(VALID_KEY_BASE64);
        String plaintext = "{\"Authorization\": \"Token key:secret\"}";

        String encrypted1 = service.encrypt(plaintext);
        String encrypted2 = service.encrypt(plaintext);

        assertNotEquals(encrypted1, encrypted2, "Two encryptions of the same plaintext must differ due to random IV");
        // Both must decrypt to the same plaintext
        assertEquals(plaintext, service.decrypt(encrypted1));
        assertEquals(plaintext, service.decrypt(encrypted2));
    }

    @Test
    @DisplayName("Tampered ciphertext is rejected (authenticated encryption)")
    void tamperedCiphertextRejected() {
        CredentialEncryptionService service = new CredentialEncryptionService(VALID_KEY_BASE64);
        String encrypted = service.encrypt("{\"Authorization\": \"Token key:secret\"}");

        // Flip a byte in the ciphertext portion (after the 12-byte IV)
        byte[] combined = java.util.Base64.getDecoder().decode(encrypted);
        combined[12] ^= 0x01; // flip first byte of ciphertext
        String tampered = java.util.Base64.getEncoder().encodeToString(combined);

        assertThrows(IllegalStateException.class, () -> service.decrypt(tampered),
                "Tampered ciphertext must fail authentication tag check");
    }

    @Test
    @DisplayName("Wrong key cannot decrypt")
    void wrongKeyRejected() {
        CredentialEncryptionService service1 = new CredentialEncryptionService(VALID_KEY_BASE64);
        CredentialEncryptionService service2 = new CredentialEncryptionService(generateDifferentKey());

        String encrypted = service1.encrypt("{\"Authorization\": \"Token key:secret\"}");

        assertThrows(IllegalStateException.class, () -> service2.decrypt(encrypted),
                "Decryption with a different key must fail");
    }

    @Test
    @DisplayName("Invalid base64 key fails at construction")
    void invalidBase64KeyRejected() {
        assertThrows(IllegalStateException.class,
                () -> new CredentialEncryptionService("not-valid-base64!!!"),
                "Invalid base64 key must fail at construction");
    }

    @Test
    @DisplayName("Wrong key length fails at construction")
    void wrongKeyLengthRejected() {
        // 16 bytes instead of 32
        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class,
                () -> new CredentialEncryptionService(shortKey),
                "16-byte key must fail at construction");

        // 48 bytes instead of 32
        String longKey = java.util.Base64.getEncoder().encodeToString(new byte[48]);
        assertThrows(IllegalStateException.class,
                () -> new CredentialEncryptionService(longKey),
                "48-byte key must fail at construction");
    }

    @Test
    @DisplayName("Blank key fails at construction")
    void blankKeyRejected() {
        assertThrows(IllegalStateException.class,
                () -> new CredentialEncryptionService(""),
                "Blank key must fail at construction");
        assertThrows(IllegalStateException.class,
                () -> new CredentialEncryptionService("   "),
                "Whitespace key must fail at construction");
    }

    @Test
    @DisplayName("Encrypted output is valid base64")
    void encryptedOutputIsBase64() {
        CredentialEncryptionService service = new CredentialEncryptionService(VALID_KEY_BASE64);
        String encrypted = service.encrypt("test");

        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(encrypted),
                "Encrypted output must be valid base64");
    }

    @Test
    @DisplayName("Empty plaintext encrypts and decrypts")
    void emptyPlaintextRoundTrip() {
        CredentialEncryptionService service = new CredentialEncryptionService(VALID_KEY_BASE64);
        String encrypted = service.encrypt("");
        assertEquals("", service.decrypt(encrypted));
    }

    private String generateDifferentKey() {
        // A different valid 32-byte key
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xFF; // different from VALID_KEY_BASE64 which starts with 0x00
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
