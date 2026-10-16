package io.netherite.audit.spi;

import java.util.Map;

/**
 * SPI for redacting sensitive fields (PII, credentials, credit card numbers, tokens) before persistence.
 */
public interface DataRedactionService {

    /**
     * Redacts sensitive data from parameter map based on enterprise minimization rules.
     */
    Map<String, Object> redactParameters(String organizationId, Map<String, Object> rawParameters);

    /**
     * Redacts sensitive data from execution response.
     */
    Map<String, Object> redactResult(String organizationId, Map<String, Object> rawResult);
}
