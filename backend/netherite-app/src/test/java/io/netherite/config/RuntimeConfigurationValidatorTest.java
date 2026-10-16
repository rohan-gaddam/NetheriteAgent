package io.netherite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime configuration validation is the fail-fast guard that makes the
 * DEFAULT profile a real enterprise runtime: PostgreSQL required (no silent H2),
 * owner account explicit, and OIDC callback configured whenever SSO is enabled.
 */
class RuntimeConfigurationValidatorTest {

    private static RuntimeConfigurationValidator.RuntimeSettings validSettings() {
        return new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://localhost:5433/netherite",
                "secret",
                "owner@enterprise.com",
                "$2b$10$hash",
                "secret-redis",
                "development",
                false,
                "http://localhost:3000",
                "http://localhost:8080");
    }

    @Test
    @DisplayName("A-1. Valid default-profile settings pass validation")
    void validSettingsPass() {
        List<String> errors = RuntimeConfigurationValidator.validate(validSettings());
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    @DisplayName("A-2. H2 datasource is rejected for the normal runtime")
    void h2DatasourceRejected() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:h2:mem:netheritedb", "", "owner@enterprise.com", "$2b$10$hash",
                "secret-redis", "development", false, "http://localhost:3000", "http://localhost:8080");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("default profile must run on PostgreSQL")),
                "Expected H2 rejection, got: " + errors);
    }

    @Test
    @DisplayName("A-3. Missing owner email fails")
    void missingOwnerEmailFails() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://localhost:5433/netherite", "secret", "", "$2b$10$hash",
                "secret-redis", "development", false, "http://localhost:3000", "http://localhost:8080");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("owner.email")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-4. Missing owner password hash fails")
    void missingOwnerPasswordHashFails() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://localhost:5433/netherite", "secret", "owner@enterprise.com", "",
                "secret-redis", "development", false, "http://localhost:3000", "http://localhost:8080");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("owner.password-hash")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-5. OIDC enabled requires a callback base URL")
    void oidcEnabledRequiresCallbackUrl() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://localhost:5433/netherite", "secret", "owner@enterprise.com", "$2b$10$hash",
                "secret-redis", "development", true, "", "");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("callback-base-url")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-6. Production requires a database password")
    void productionRequiresDbPassword() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://host/netherite", "", "owner@enterprise.com", "$2b$10$hash",
                "secret-redis", "production", true, "https://control.example.com", "https://idp.example.com");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("DB_PASSWORD")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-7. Production requires an HTTPS OIDC callback")
    void productionRequiresHttpsCallback() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://host/netherite", "secret", "owner@enterprise.com", "$2b$10$hash",
                "secret-redis", "production", true, "http://localhost:3000", "http://idp.example.com");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("HTTPS")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-8. Multiple misconfigurations are all reported")
    void multipleErrorsReported() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:h2:mem:netheritedb", "", "", "",
                "", "production", true, "http://example.com", "https://idp.example.com");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        // H2 rejection, owner email, owner password hash, DB_PASSWORD, REDIS_PASSWORD,
        // and HTTPS callback
        assertEquals(6, errors.size(), "Expected all 6 misconfigurations, got: " + errors);
    }

    @Test
    @DisplayName("A-9. Production requires a Redis password")
    void productionRequiresRedisPassword() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://host/netherite", "secret", "owner@enterprise.com", "$2b$10$hash",
                "", "production", false, "https://control.example.com", "https://idp.example.com");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("REDIS_PASSWORD")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-10. OIDC enabled requires an external base URL")
    void oidcEnabledRequiresExternalBaseUrl() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://localhost:5433/netherite", "secret", "owner@enterprise.com", "$2b$10$hash",
                "secret-redis", "development", true, "http://localhost:3000", "");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("external-base-url")), "Got: " + errors);
    }

    @Test
    @DisplayName("A-11. Production requires an HTTPS OIDC external base URL")
    void productionRequiresHttpsExternalBaseUrl() {
        var settings = new RuntimeConfigurationValidator.RuntimeSettings(
                "jdbc:postgresql://host/netherite", "secret", "owner@enterprise.com", "$2b$10$hash",
                "secret-redis", "production", true, "https://control.example.com", "http://idp.example.com");
        List<String> errors = RuntimeConfigurationValidator.validate(settings);
        assertTrue(errors.stream().anyMatch(e -> e.contains("external-base-url") && e.contains("HTTPS")),
                "Got: " + errors);
    }
}