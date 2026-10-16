package io.netherite.audit.service;

import io.netherite.audit.spi.DataRedactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts sensitive fields from parameters and results before audit persistence.
 * Patterns: credit card numbers, SSNs, email addresses, tokens, passwords, secrets, API keys.
 */
@Slf4j
@Service
public class DefaultDataRedactionService implements DataRedactionService {

    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "apikey", "api_key", "authorization",
            "creditCard", "credit_card", "ssn", "socialSecurity",
            "privateKey", "private_key", "secretHash", "secret_hash",
            "bearer", "credentials", "accessToken", "access_token",
            "refreshToken", "refresh_token", "sessionToken", "session_token"
    );

    private static final String REDACTED = "[REDACTED]";

    @Override
    public Map<String, Object> redactParameters(String organizationId, Map<String, Object> rawParameters) {
        if (rawParameters == null || rawParameters.isEmpty()) {
            return Map.of();
        }
        return redactMap(rawParameters);
    }

    @Override
    public Map<String, Object> redactResult(String organizationId, Map<String, Object> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            return Map.of();
        }
        return redactMap(rawResult);
    }

    private Map<String, Object> redactMap(Map<String, Object> input) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (isSensitiveKey(key)) {
                redacted.put(key, REDACTED);
            } else if (value instanceof String strValue) {
                redacted.put(key, redactStringValue(strValue));
            } else if (value instanceof Map<?, ?> mapValue) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) mapValue;
                redacted.put(key, redactMap(nested));
            } else {
                redacted.put(key, value);
            }
        }
        return redacted;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(sk -> lower.contains(sk.toLowerCase()));
    }

    private String redactStringValue(String value) {
        if (value == null) return null;
        String result = CREDIT_CARD_PATTERN.matcher(value).replaceAll(REDACTED);
        result = SSN_PATTERN.matcher(result).replaceAll(REDACTED);
        result = EMAIL_PATTERN.matcher(result).replaceAll(REDACTED);
        return result;
    }
}
