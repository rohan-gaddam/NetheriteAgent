package io.netherite.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secret-hygiene regression gate (WS5 Batch G).
 *
 * Locks in the contract that no real secret value can ever be baked into the
 * repository files that produce the runtime configuration:
 *   - src/main/resources/application.yml must hold NO literal secret - the four
 *     secret-bearing keys must be environment placeholders (or blank), so the
 *     value always arrives from the container entrypoint (file-based compose
 *     secrets), never from the image.
 *   - infrastructure/docker/docker-compose.yml must reference exactly the five
 *     host secret files under ./secrets/, and the app service must mount only
 *     the runtime secrets it actually consumes (least privilege: the admin
 *     db_password never rides the app service).
 *   - docker-entrypoint.sh must require precisely the secrets the compose app
 *     service mounts.
 *
 * Pure unit-style test (no Spring context); anything that trips it is a
 * secret-baking regression, not a config-parse problem.
 */
class SecretHygieneRegressionTest {

    private static final List<String> SECRET_FILES = List.of(
            "db_password", "db_app_password", "redis_password", "opa_policy_token", "owner_password_hash");

    private static final List<String> APP_SECRET_MOUNTS =
            List.of("db_app_password", "redis_password", "owner_password_hash", "opa_policy_token");

    private static final Pattern LITERAL_BCRYPT = Pattern.compile("\\$2[aby]\\$\\d+\\$");
    private static final Pattern LITERAL_LONG_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    @Test
    @DisplayName("G-1. Main application.yml carries no literal secret value")
    void mainApplicationYmlHasNoBakedSecrets() throws Exception {
        String yml = read("src/main/resources/application.yml");
        assertFalse(LITERAL_BCRYPT.matcher(yml).find(), "main application.yml must not contain a literal BCrypt hash");
        assertFalse(LITERAL_LONG_HEX.matcher(yml).find(), "main application.yml must not contain a literal 64-hex secret");
        assertFalse(yml.contains("ntk_live_"), "main application.yml must not contain a literal agent API key");
        assertFalse(yml.contains("AKIA"), "main application.yml must not contain a literal AWS access key");
    }

    @Test
    @DisplayName("G-2. Production secret keys resolve from environment placeholders, never literals")
    void secretKeysUseEnvironmentPlaceholders() throws Exception {
        String yml = read("src/main/resources/application.yml");

        // Each production secret-bearing binding must be `${ENV:}` (or at least a
        // placeholder), so the container entrypoint injects the file-based value.
        for (String binding : List.of(
                "password: ${DB_PASSWORD:}",
                "password: ${REDIS_PASSWORD:}",
                "token: ${OPA_TOKEN:}",
                "password-hash: ${OWNER_PASSWORD_HASH:}",
                "secret: ${WEBHOOK_SECRET:}",
                "password: ${MAIL_PASSWORD:}")) {
            assertTrue(yml.contains(binding), "main application.yml must bind exactly '" + binding + "'");
        }
    }

    @Test
    @DisplayName("G-3. Compose inventories exactly the five host secret files")
    void composeDeclaresExactlyTheFiveSecrets() throws Exception {
        String compose = read("../../infrastructure/docker/docker-compose.yml");
        String block = block(compose, "secrets:", "services:");
        for (String name : SECRET_FILES) {
            assertTrue(block.contains(name + ":"), "compose secrets: block must declare '" + name + "'");
            assertTrue(block.contains("file: ./secrets/" + name),
                    "compose secrets: '" + name + "' must source ./secrets/" + name);
        }
    }

    @Test
    @DisplayName("G-4. App service mounts exactly its runtime secrets (least privilege)")
    void appServiceMountsOnlyRuntimeSecrets() throws Exception {
        String compose = read("../../infrastructure/docker/docker-compose.yml");
        String appBlock = block(compose, "  app:", "networks:");

        String secretSection = appBlock.substring(appBlock.indexOf("secrets:"));
        for (String mount : APP_SECRET_MOUNTS) {
            assertTrue(secretSection.contains("- " + mount), "app service must mount '" + mount + "'");
        }
        assertFalse(secretSection.contains("- db_password"),
                "app service must NOT mount the admin db_password (least privilege)");
    }

    @Test
    @DisplayName("G-5. Container entrypoint requires exactly the secrets the app mounts")
    void entrypointRequiresExactlyTheAppSecretSet() throws Exception {
        String entry = read("docker-entrypoint.sh");
        assertTrue(entry.contains("DB_SECRET_NAME"), "entrypoint must use DB_SECRET_NAME for the DB credential");
        for (String name : APP_SECRET_MOUNTS) {
            assertTrue(entry.contains(name), "entrypoint must require secret '" + name + "'");
        }
        assertFalse(entry.contains("require_secret db_password"), "runtime entrypoint must not require admin db_password");
    }

    private static String block(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertTrue(start >= 0, "marker not found: '" + startMarker + "'");
        int end = text.indexOf(endMarker, start + startMarker.length());
        return text.substring(start, end >= 0 ? end : text.length());
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}