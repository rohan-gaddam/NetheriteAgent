package io.netherite.connectors.ssrf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfGuardValidatorImplTest {

    private SsrfGuardValidatorImpl validator;

    @BeforeEach
    void setUp() {
        validator = new SsrfGuardValidatorImpl(List.of(
                "127.0.0.0/8",
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "169.254.169.254/32"
        ));
    }

    @Test
    @DisplayName("Blocks loopback IPv4 address")
    void testBlocksLoopback() {
        assertFalse(validator.isUriPermitted(URI.create("http://127.0.0.1:8080/secret")));
        assertFalse(validator.isUriPermitted(URI.create("http://127.0.0.2/admin")));
        assertFalse(validator.isUriPermitted(URI.create("https://127.0.0.1/")));
    }

    @Test
    @DisplayName("Blocks localhost hostname")
    void testBlocksLocalhost() {
        assertFalse(validator.isUriPermitted(URI.create("http://localhost:8080/admin")));
        assertFalse(validator.isUriPermitted(URI.create("https://localhost/")));
    }

    @Test
    @DisplayName("Blocks RFC1918 10.x.x.x range")
    void testBlocks10Range() {
        assertFalse(validator.isUriPermitted(URI.create("http://10.0.0.1/internal")));
        assertFalse(validator.isUriPermitted(URI.create("http://10.255.255.255/")));
        assertFalse(validator.isUriPermitted(URI.create("http://10.0.1.1:9090/api")));
    }

    @Test
    @DisplayName("Blocks RFC1918 172.16-31.x.x range")
    void testBlocks172Range() {
        assertFalse(validator.isUriPermitted(URI.create("http://172.16.0.1/private")));
        assertFalse(validator.isUriPermitted(URI.create("http://172.31.255.255/")));
        assertFalse(validator.isUriPermitted(URI.create("http://172.20.0.1:8080/")));
    }

    @Test
    @DisplayName("Blocks RFC1918 192.168.x.x range")
    void testBlocks192Range() {
        assertFalse(validator.isUriPermitted(URI.create("http://192.168.1.1/router")));
        assertFalse(validator.isUriPermitted(URI.create("http://192.168.0.255/")));
    }

    @Test
    @DisplayName("Blocks cloud metadata endpoint")
    void testBlocksMetadataEndpoint() {
        assertFalse(validator.isUriPermitted(URI.create("http://169.254.169.254/latest/meta-data/")));
        assertFalse(validator.isUriPermitted(URI.create("http://169.254.169.254/latest/user-data/")));
    }

    @Test
    @DisplayName("Permits safe public HTTPS URLs with IP addresses")
    void testPermitsSafeUrls() {
        // Test with public IPs directly (no DNS resolution needed)
        assertTrue(validator.isUriPermitted(URI.create("https://93.184.216.34/payment")));
        assertTrue(validator.isUriPermitted(URI.create("http://93.184.216.34:8080/data")));
        assertTrue(validator.isUriPermitted(URI.create("https://151.101.1.140/api")));
    }

    @Test
    @DisplayName("Permits URLs with hostnames that resolve to public IPs")
    void testPermitsResolvableHostnames() {
        assertTrue(validator.isUriPermitted(URI.create("https://google.com/")));
        assertTrue(validator.isUriPermitted(URI.create("https://github.com/")));
    }

    @Test
    @DisplayName("Rejects null URI")
    void testRejectsNull() {
        assertFalse(validator.isUriPermitted(null));
    }

    @Test
    @DisplayName("Rejects non-HTTP schemes")
    void testRejectsNonHttpSchemes() {
        assertFalse(validator.isUriPermitted(URI.create("ftp://example.com/file")));
        assertFalse(validator.isUriPermitted(URI.create("file:///etc/passwd")));
        assertFalse(validator.isUriPermitted(URI.create("javascript:alert(1)")));
    }

    @Test
    @DisplayName("Rejects URI with null host")
    void testRejectsNullHost() {
        assertFalse(validator.isUriPermitted(URI.create("http:///path")));
    }

    @Test
    @DisplayName("Blocks well-known internal management ports")
    void testBlocksSensitivePorts() {
        assertFalse(validator.isUriPermitted(URI.create("http://93.184.216.34:3306/")));
        assertFalse(validator.isUriPermitted(URI.create("http://93.184.216.34:6379/")));
        assertFalse(validator.isUriPermitted(URI.create("http://93.184.216.34:27017/db")));
        assertFalse(validator.isUriPermitted(URI.create("http://93.184.216.34:2375/containers")));
        assertFalse(validator.isUriPermitted(URI.create("http://93.184.216.34:22/")));
        assertFalse(validator.isUriPermittedForOperatorConfigured(URI.create("http://93.184.216.34:5432/")));
    }

    @Test
    @DisplayName("Permits standard web ports and default port -1")
    void testPermitsWebPorts() {
        assertTrue(validator.isUriPermitted(URI.create("http://93.184.216.34/")));
        assertTrue(validator.isUriPermitted(URI.create("https://93.184.216.34:443/payment")));
        assertTrue(validator.isUriPermitted(URI.create("http://93.184.216.34:8080/data")));
        assertTrue(validator.isUriPermittedForOperatorConfigured(URI.create("http://93.184.216.34:8443/operator")));
    }

    @Test
    @DisplayName("Rejects unresolvable hostnames")
    void testRejectsUnresolvableHost() {
        assertFalse(validator.isUriPermitted(URI.create("https://this-host-does-not-exist-12345.invalid/")));
    }

    @Test
    @DisplayName("Permits the exact configured AI-ERP private destination")
    void testPermitsExactConfiguredAiErpDestination() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("10.20.30.40:5000", "[fdc4:f303:9324::254]:5000");
        assertTrue(allowlisted.isUriPermitted(URI.create("http://10.20.30.40:5000/api/v3.0/p/agent/schema")));
        assertTrue(allowlisted.isUriPermitted(URI.create("http://[fdc4:f303:9324::254]:5000/api/v3.0/p/agent/schema")));
    }

    @Test
    @DisplayName("Blocks unapproved loopback when a different destination is allowlisted")
    void testBlocksUnapprovedLoopback() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("10.20.30.40:5000");
        assertFalse(allowlisted.isUriPermitted(URI.create("http://127.0.0.1:5000/")));
        assertFalse(allowlisted.isUriPermitted(URI.create("http://localhost:5000/")));
        assertFalse(allowlisted.isUriPermitted(URI.create("http://127.0.0.2:5000/")));
    }

    @Test
    @DisplayName("Blocks unapproved RFC1918 destination")
    void testBlocksUnapprovedPrivate() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("10.20.30.40:5000");
        assertFalse(allowlisted.isUriPermitted(URI.create("http://192.168.1.10:5000/")));
        assertFalse(allowlisted.isUriPermitted(URI.create("http://172.16.0.1:5000/")));
        assertFalse(allowlisted.isUriPermitted(URI.create("http://10.20.30.41:5000/")));
    }

    @Test
    @DisplayName("Blocks hostname resolving to a prohibited address when not allowlisted")
    void testBlocksHostnameResolvingToProhibitedAddress() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("localhost:5001");
        assertFalse(allowlisted.isUriPermitted(URI.create("http://localhost:5000/")));
    }

    @Test
    @DisplayName("Allowlist remains scoped to the exact host and port")
    void testAllowlistScopeDoesNotWiden() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("10.20.30.40:5000");
        assertFalse(allowlisted.isUriPermitted(URI.create("http://10.20.30.40:5001/")));
        assertFalse(allowlisted.isUriPermitted(URI.create("http://10.20.30.40/")));
    }

    @Test
    @DisplayName("Blocks link-local and metadata even when that exact destination is allowlisted")
    void testAllowlistStillBlocksLinkLocalAndMetadata() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("169.254.169.254:80", "[fe80::1]:5000");
        assertFalse(allowlisted.isUriPermitted(URI.create("http://169.254.169.254/latest/meta-data/")));
        assertFalse(allowlisted.isUriPermitted(URI.create("http://[fe80::1]:5000/")));
    }

    @Test
    @DisplayName("Allowlist still enforces the blocked-port policy")
    void testAllowlistStillEnforcesBlockedPorts() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("10.20.30.40:5432");
        assertFalse(allowlisted.isUriPermitted(URI.create("http://10.20.30.40:5432/")));
    }

    @Test
    @DisplayName("Permits normal public destinations unrelated to the allowlist")
    void testPermitsNormalPublicDestinations() {
        SsrfGuardValidatorImpl allowlisted = allowlisted("10.20.30.40:5000");
        assertTrue(allowlisted.isUriPermitted(URI.create("https://93.184.216.34/payment")));
        assertTrue(allowlisted.isUriPermitted(URI.create("http://151.101.1.140:8080/api")));
    }

    private static SsrfGuardValidatorImpl allowlisted(String... destinations) {
        return new SsrfGuardValidatorImpl(List.of(), List.of(), false, List.of(destinations));
    }
}
