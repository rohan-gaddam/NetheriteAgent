package io.netherite.security.key;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceCredentialServiceTest {

    private static final String VALID_KEY_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Credentials are encrypted as a JSON object and decrypt only for storage consumers")
    void encryptsCredentialsForStorage() throws Exception {
        CredentialEncryptionService encryptionService = new CredentialEncryptionService(VALID_KEY_BASE64);
        ResourceCredentialService service = serviceWith(encryptionService);

        String encrypted = service.encryptForStorage(Map.of("Authorization", "Token test-key:test-secret"));

        assertFalse(encrypted.contains("test-key"));
        assertFalse(encrypted.contains("test-secret"));

        Map<String, String> decrypted = OBJECT_MAPPER.readValue(
                encryptionService.decrypt(encrypted), new TypeReference<Map<String, String>>() { });
        assertEquals(Map.of("Authorization", "Token test-key:test-secret"), decrypted);
    }

    @Test
    @DisplayName("Absent credentials produce no stored credential record")
    void nullCredentialsProduceNull() {
        ResourceCredentialService service = serviceWith(new CredentialEncryptionService(VALID_KEY_BASE64));

        assertNull(service.encryptForStorage(null));
    }

    @Test
    @DisplayName("Empty or invalid credential maps are rejected without persisting anything")
    void invalidCredentialMapsRejected() {
        ResourceCredentialService service = serviceWith(new CredentialEncryptionService(VALID_KEY_BASE64));

        assertInvalid(service, Map.of());
        assertInvalid(service, Map.of(" ", "value"));
        assertInvalid(service, Map.of("Authorization", " "));
        assertInvalid(service, Map.of("Authorization", "Token key\r\nInjected: yes"));
        assertInvalid(service, Map.of("Authorization", "Token key\nvalue"));

        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("Authorization", null);
        assertInvalid(service, nullValue);
    }

    @Test
    @DisplayName("Missing encryption key fails closed and never returns plaintext")
    void missingEncryptionKeyFailsClosed() {
        ResourceCredentialService service = serviceWith(null);

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.encryptForStorage(Map.of("Authorization", "Token key:secret")));

        assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, thrown.getErrorCode());
        assertTrue(thrown.getMessage().toLowerCase().contains("encryption"));
    }

    @Test
    @DisplayName("Encryption failure fails closed without echoing credential material")
    void encryptionFailureFailsClosed() {
        CredentialEncryptionService failing = mock(CredentialEncryptionService.class);
        when(failing.encrypt(anyString())).thenThrow(new IllegalStateException("cipher failure"));
        ResourceCredentialService service = serviceWith(failing);

        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.encryptForStorage(Map.of("Authorization", "Token key:secret")));

        assertEquals(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE, thrown.getErrorCode());
        assertFalse(String.valueOf(thrown.getMessage()).contains("secret"));
        assertFalse(String.valueOf(thrown.getMessage()).contains("Token key"));
    }

    private void assertInvalid(ResourceCredentialService service, Map<String, String> credentials) {
        NetheriteException thrown = assertThrows(NetheriteException.class,
                () -> service.encryptForStorage(credentials));
        assertEquals(SystemErrorCode.ERR_INVALID_PARAMETERS, thrown.getErrorCode());
    }

    private ResourceCredentialService serviceWith(CredentialEncryptionService encryptionService) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        if (encryptionService != null) {
            beanFactory.registerSingleton("credentialEncryptionService", encryptionService);
        }
        return new ResourceCredentialService(
                beanFactory.getBeanProvider(CredentialEncryptionService.class));
    }
}
