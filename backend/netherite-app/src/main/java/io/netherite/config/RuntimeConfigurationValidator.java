package io.netherite.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast startup configuration validation for the enterprise runtime.
 *
 * The DEFAULT profile is a real runtime (PostgreSQL, Flyway, ddl-auto: validate,
 * enterprise OIDC once provisioned). Misconfiguration must fail with a clear
 * message at startup, never silently degrade to an in-memory database or fake
 * authentication. The test profile is exempt — it is allowed to use H2 for tests.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RuntimeConfigurationValidator implements ApplicationRunner {

    public static final String H2_DATASOURCE_PREFIX = "jdbc:h2:";

    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        RuntimeSettings settings = collect(environment);
        List<String> errors = validate(settings);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid runtime configuration detected. Refusing to start. Errors:\n  - "
                            + String.join("\n  - ", errors));
        }
        log.info("Runtime configuration validation passed (profile={}, netherite.environment={}, "
                        + "oidcEnabled={}, datasource={})",
                environment.getProperty("spring.profiles.active", "default"),
                settings.netheriteEnvironment(),
                settings.oidcEnabled(),
                settings.datasourceUrl());
    }

    private static RuntimeSettings collect(Environment env) {
        return new RuntimeSettings(
                env.getProperty("spring.datasource.url", ""),
                env.getProperty("spring.datasource.password", ""),
                env.getProperty("netherite.auth.owner.email", ""),
                env.getProperty("netherite.auth.owner.password-hash", ""),
                env.getProperty("spring.data.redis.password", ""),
                env.getProperty("netherite.environment", "development").toLowerCase(),
                env.getProperty("netherite.auth.oidc.enabled", "false").equalsIgnoreCase("true"),
                env.getProperty("netherite.auth.oidc.callback-base-url", ""),
                env.getProperty("netherite.auth.oidc.external-base-url", ""));
    }

    /**
     * Pure validation logic, independent of Spring, for direct unit testing.
     */
    public static List<String> validate(RuntimeSettings settings) {
        List<String> errors = new ArrayList<>();

        // Never silently use an in-memory database for the normal runtime.
        if (settings.datasourceUrl() != null && settings.datasourceUrl().startsWith(H2_DATASOURCE_PREFIX)) {
            errors.add("The default profile must run on PostgreSQL (ddl-auto: validate). "
                    + "Detected H2 datasource '" + settings.datasourceUrl()
                    + "' — an in-memory database is only valid for the test profile.");
        }

        // The owner account is the configured bootstrap exception; it must be explicit.
        if (settings.ownerEmail() == null || settings.ownerEmail().isBlank()) {
            errors.add("netherite.auth.owner.email must be configured (OWNER_EMAIL).");
        }
        if (settings.ownerPasswordHash() == null || settings.ownerPasswordHash().isBlank()) {
            errors.add("netherite.auth.owner.password-hash must be configured (OWNER_PASSWORD_HASH). "
                    + "The owner password login must never silently fall back to a default.");
        }

        if (settings.oidcEnabled()) {
            if (settings.oidcCallbackBaseUrl() == null || settings.oidcCallbackBaseUrl().isBlank()) {
                errors.add("netherite.auth.oidc.callback-base-url must be configured when OIDC is enabled "
                        + "(OIDC_CALLBACK_BASE_URL).");
            }
            // The backend external base URL feeds the redirect_uri registered
            // with the IdP. Without it the IdP rejects the flow; failing here
            // surfaces the misconfiguration at startup instead of mid-login.
            if (settings.oidcExternalBaseUrl() == null || settings.oidcExternalBaseUrl().isBlank()) {
                errors.add("netherite.auth.oidc.external-base-url must be configured when OIDC is enabled "
                        + "(OIDC_EXTERNAL_BASE_URL).");
            }
        }

        // Production-environment guardrails: no blank broker/database secrets, HTTPS callbacks.
        // File-based docker secrets are the supported mechanism; a missing secret file
        // yields an empty value here and must never start an unauthenticated broker/DB.
        if ("production".equals(settings.netheriteEnvironment())) {
            if (settings.dbPassword() == null || settings.dbPassword().isBlank()) {
                errors.add("spring.datasource.password must be configured in production (DB_PASSWORD).");
            }
            if (settings.redisPassword() == null || settings.redisPassword().isBlank()) {
                errors.add("spring.data.redis.password must be configured in production (REDIS_PASSWORD). "
                        + "An unauthenticated coordination broker is never acceptable in production.");
            }
            if (settings.oidcEnabled()
                    && (settings.oidcCallbackBaseUrl() == null || !settings.oidcCallbackBaseUrl().startsWith("https://"))) {
                errors.add("netherite.auth.oidc.callback-base-url must use HTTPS in production.");
            }
            if (settings.oidcEnabled()
                    && (settings.oidcExternalBaseUrl() == null || !settings.oidcExternalBaseUrl().startsWith("https://"))) {
                errors.add("netherite.auth.oidc.external-base-url must use HTTPS in production.");
            }
        }

        return errors;
    }

    public record RuntimeSettings(
            String datasourceUrl,
            String dbPassword,
            String ownerEmail,
            String ownerPasswordHash,
            String redisPassword,
            String netheriteEnvironment,
            boolean oidcEnabled,
            String oidcCallbackBaseUrl,
            String oidcExternalBaseUrl
    ) {
    }
}