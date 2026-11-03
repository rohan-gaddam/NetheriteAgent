package io.netherite.security.key;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates and encrypts resource credentials before they are persisted.
 *
 * <p>Plaintext credentials are accepted only on the resource create/update API. This
 * service validates the supplied map, serializes it as a JSON object, and encrypts the
 * result with {@link CredentialEncryptionService} (AES-256-GCM). The returned value is
 * the only representation that may be written to {@code resources.encrypted_credentials}.
 *
 * <p>If no encryption key is configured the service fails closed with a 503-style
 * {@link SystemErrorCode#ERR_SYSTEM_UNAVAILABLE}; plaintext credentials are never
 * persisted. Error messages never include supplied credential keys or values.
 */
@Service
public class ResourceCredentialService {

    private static final int MAX_CREDENTIALS = 32;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 8192;

    private final ObjectProvider<CredentialEncryptionService> encryptionServiceProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResourceCredentialService(ObjectProvider<CredentialEncryptionService> encryptionServiceProvider) {
        this.encryptionServiceProvider = encryptionServiceProvider;
    }

    /**
     * Validates and encrypts a plaintext credential map for persistence.
     *
     * @param credentials plaintext credential map supplied by the caller, or {@code null}
     * @return encrypted payload, or {@code null} when no credentials were supplied
     * @throws NetheriteException if the map is invalid, encryption is not configured, or
     *         encryption fails; the message never contains credential material
     */
    public String encryptForStorage(Map<String, String> credentials) {
        if (credentials == null) {
            return null;
        }
        Map<String, String> validated = validate(credentials);

        CredentialEncryptionService encryptionService;
        try {
            encryptionService = encryptionServiceProvider.getIfAvailable();
        } catch (RuntimeException e) {
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Credential encryption is not available", e);
        }
        if (encryptionService == null) {
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Credential encryption is not configured; refusing to persist credentials");
        }

        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(validated);
        } catch (JsonProcessingException e) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Credentials could not be serialized", e);
        }

        try {
            return encryptionService.encrypt(serialized);
        } catch (RuntimeException e) {
            throw new NetheriteException(SystemErrorCode.ERR_SYSTEM_UNAVAILABLE,
                    "Credential encryption failed", e);
        }
    }

    private Map<String, String> validate(Map<String, String> credentials) {
        if (credentials.isEmpty()) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "At least one credential is required when credentials are supplied");
        }
        if (credentials.size() > MAX_CREDENTIALS) {
            throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                    "Too many credentials supplied");
        }

        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                        "Credential names must not be blank");
            }
            if (key.length() > MAX_KEY_LENGTH) {
                throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                        "Credential name is too long");
            }
            if (value == null || value.isBlank()) {
                throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                        "Credential values must not be blank");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                        "Credential value is too long");
            }
            if (containsControlCharacters(key) || containsControlCharacters(value)) {
                throw new NetheriteException(SystemErrorCode.ERR_INVALID_PARAMETERS,
                        "Credentials must not contain control characters");
            }
            validated.put(key, value);
        }
        return validated;
    }

    private boolean containsControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
