package io.netherite.security.rate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the production rate-limit defaults baked into {@code src/main/resources/application.yml}.
 * These numbers are the actual limits shipped to deployments, so a regression here (a route
 * silently losing its fail-closed flag, an IP-only login table, a disabled limiter) must fail.
 */
class RateLimitProductionDefaultsTest {

    private static final Path PROD_YAML = Path.of("src", "main", "resources", "application.yml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> productionRateLimit(@SuppressWarnings("rawtypes") Map root) {
        return (Map<String, Object>) ((Map<String, Object>) root.get("netherite")).get("rate-limit");
    }

    @Test
    @DisplayName("Production YAML declares the rate-limit subsystem with a full route table")
    void productionYamlDeclaresRateLimitTable() throws Exception {
        assertTrue(Files.exists(PROD_YAML), "test must run from the netherite-app module directory");

        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource(PROD_YAML.toFile()));
        Map<String, Object> root = yaml.getObject();
        Map<String, Object> rateLimit = productionRateLimit(root);

        assertNotNull(rateLimit);
        Object enabled = rateLimit.get("enabled");
        assertTrue(enabled != null && String.valueOf(enabled).contains("true"),
                "rate limiting must default to enabled in production (found: " + enabled + ")");
    }

    @Test
    @DisplayName("Every protected surface ships with a fail-closed flag where required")
    void securitySensitiveRoutesAreFailClosed() throws Exception {
        List<Map<String, Object>> routes = routesFromProduction();
        Map<String, Boolean> failClosedByName = new java.util.HashMap<>();

        for (Map<String, Object> route : routes) {
            String name = (String) route.get("name");
            failClosedByName.put(name, Boolean.parseBoolean(String.valueOf(route.getOrDefault("fail-closed", "false"))));
        }

        for (String securitySensitive : List.of(
                "owner-login", "oidc", "agent-execution", "gateway-execute",
                "approval-decision", "approval-reject", "admin-agents", "admin-resources",
                "admin-bindings", "admin-policies", "admin-users", "control-plane")) {
            assertTrue(failClosedByName.getOrDefault(securitySensitive, false),
                    "route '%s' must be fail-closed".formatted(securitySensitive));
        }

        // Non-security-critical read surfaces must NOT be fail-closed (no full-site outage on Redis down)
        for (String failOpen : List.of("gateway-integration", "approval-queue", "audit-read", "dashboard")) {
            assertFalse(failClosedByName.getOrDefault(failOpen, true),
                    "route '%s' must remain fail-open".formatted(failOpen));
        }
    }

    @Test
    @DisplayName("Login is protected by IP, per-account-IP and per-account buckets")
    void loginHasMultiDimensionBuckets() throws Exception {
        List<Map<String, Object>> routes = routesFromProduction();
        Map<String, List<Map<String, Object>>> bucketsByName = new java.util.HashMap<>();

        for (Map<String, Object> route : routes) {
            Object raw = route.get("buckets");
            if (raw instanceof List<?> bucketList) {
                bucketsByName.put((String) route.get("name"),
                        bucketList.stream().map(b -> (Map<String, Object>) b).toList());
            }
        }

        List<String> loginDims = bucketsByName.get("owner-login").stream()
                .map(b -> (String) b.get("dimension"))
                .toList();
        assertTrue(loginDims.contains("IP"));
        assertTrue(loginDims.contains("LOGIN_ACCOUNT_IP"));
        assertTrue(loginDims.contains("LOGIN_ACCOUNT"));
    }

    @Test
    @DisplayName("Execution surfaces carry an organization bucket so no single tenant can flood shared broker")
    void executionHasOrganizationBucket() throws Exception {
        List<Map<String, Object>> routes = routesFromProduction();
        boolean orgBucketFound = false;
        for (Map<String, Object> route : routes) {
            if (List.of("agent-execution", "gateway-execute").contains(route.get("name"))) {
                Object raw = route.get("buckets");
                if (raw instanceof List<?> bucketList) {
                    for (Object b : bucketList) {
                        if ("ORGANIZATION".equals(((Map<String, Object>) b).get("dimension"))) {
                            orgBucketFound = true;
                        }
                    }
                }
            }
        }
        assertTrue(orgBucketFound, "execution routes must enforce an organization-scoped bucket");
    }

    @Test
    @DisplayName("Trusted-proxy header handling is enabled so IP keys reflect the real client")
    void forwardHeadersStrategyIsNative() throws Exception {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource(PROD_YAML.toFile()));
        Map<String, Object> root = yaml.getObject();
        Map<String, Object> server = (Map<String, Object>) root.get("server");

        assertEquals("native", String.valueOf(server.get("forward-headers-strategy")),
                "server.forward-headers-strategy must be native for trustworthy client IPs behind the proxy");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routesFromProduction() throws Exception {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource(PROD_YAML.toFile()));
        Map<String, Object> root = yaml.getObject();
        return (List<Map<String, Object>>) productionRateLimit(root).get("routes");
    }
}