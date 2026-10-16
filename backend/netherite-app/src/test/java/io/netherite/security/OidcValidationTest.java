package io.netherite.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netherite.auth.service.OidcService;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OIDC token validation, Control Plane Admin authorization,
 * and cross-tenant identity rejection using REAL JWTs signed with a REAL RSA key.
 *
 * These tests exercise the actual OidcService validation logic — no mocking
 * of the authentication core.
 */
@SpringBootTest
@ActiveProfiles("test")
class OidcValidationTest {

    private static RSAKey rsaKey;
    private static String keyId;

    @Autowired private OidcService oidcService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationIdpConfigRepository idpConfigRepository;
    @Autowired private UserRepository userRepository;

    private OrganizationEntity testOrg;
    private OrganizationIdpConfigEntity testIdpConfig;

    @BeforeAll
    static void generateRsaKey() throws Exception {
        rsaKey = new RSAKey.Builder(new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate())
                .keyID("test-key-1")
                .build();
        keyId = "test-key-1";
    }

    @BeforeEach
    void setUp() {
        // Clean up
        organizationRepository.findBySlug("oidc-validation-test").ifPresent(o -> {
            idpConfigRepository.findByOrganizationId(o.getId()).ifPresent(idpConfigRepository::delete);
            userRepository.findAllByOrganizationId(o.getId()).forEach(userRepository::delete);
            organizationRepository.delete(o);
        });

        testOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("OIDC Validation Test Org")
                .slug("oidc-validation-test")
                .status("ACTIVE")
                .build());

        testIdpConfig = idpConfigRepository.save(OrganizationIdpConfigEntity.builder()
                .organizationId(testOrg.getId())
                .issuerUrl("https://test-issuer.example.com")
                .clientId("test-client")
                .adminClaimName("roles")
                .adminClaimValue("netherite-admin")
                .claimEmail("email")
                .claimName("name")
                .status("ACTIVE")
                .build());
    }

    private SignedJWT createSignedJwt(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt;
    }

    private JWTClaimsSet.Builder baseClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://test-issuer.example.com")
                .subject("user-123")
                .audience("test-client")
                .expirationTime(Date.from(now.plusSeconds(300)))
                .issueTime(Date.from(now.minusSeconds(10)))
                .claim("email", "alice@oidc-test.example.com")
                .claim("email_verified", true)
                .claim("name", "Alice Test");
    }

    // ===== Test 9: Issuer validation =====

    @Test
    @DisplayName("9a. ID token with wrong issuer is rejected")
    void wrongIssuerRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .issuer("https://evil-issuer.example.com")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        null,
                        testIdpConfig));
        assertTrue(ex.getMessage().contains("issuer"), "Error should mention issuer");
    }

    // ===== Test 10: Token validation (audience, expiration) =====

    @Test
    @DisplayName("10a. ID token with wrong audience is rejected")
    void wrongAudienceRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .audience("wrong-audience")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        null,
                        testIdpConfig));
    }

    @Test
    @DisplayName("10b. Expired ID token is rejected")
    void expiredTokenRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        null,
                        testIdpConfig));
        assertTrue(ex.getMessage().contains("expired"), "Error should mention expiration");
    }

    // ===== Test 7: Nonce validation =====

    @Test
    @DisplayName("7a. ID token with wrong nonce is rejected")
    void wrongNonceRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .claim("nonce", "wrong-nonce-value")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        "expected-nonce-value",
                        testIdpConfig));
        assertTrue(ex.getMessage().contains("nonce"), "Error should mention nonce");
    }

    @Test
    @DisplayName("7b. ID token with correct nonce passes nonce validation")
    void correctNonceAccepted() throws Exception {
        // Note: signature validation requires JWKS fetch — this tests nonce logic
        // by expecting failure on JWKS (not nonce)
        JWTClaimsSet claims = baseClaims()
                .claim("nonce", "expected-nonce-value")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        // This will fail at JWKS fetch (no server) but NOT at nonce check
        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        "expected-nonce-value",
                        testIdpConfig));
        assertFalse(ex.getMessage().contains("nonce"),
                "Should NOT fail on nonce — nonce is correct");
    }

    // ===== Test 8: PKCE is stored in flow =====

    @Autowired private io.netherite.domain.repository.OidcFlowRepository oidcFlowRepository;

    @Test
    @DisplayName("8. OIDC flow stores PKCE code_verifier")
    void pkceStoredInFlow() throws Exception {
        io.netherite.domain.entity.OidcFlowEntity flow =
                io.netherite.domain.entity.OidcFlowEntity.builder()
                        .state(UUID.randomUUID().toString())
                        .nonce(UUID.randomUUID().toString())
                        .codeVerifier("test-code-verifier-abc123")
                        .organizationId(testOrg.getId())
                        .idpConfigId(testIdpConfig.getId())
                        .redirectUri("http://localhost:8080/v1/auth/oidc/callback")
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        oidcFlowRepository.save(flow);

        var reloaded = oidcFlowRepository.findByState(flow.getState());
        assertTrue(reloaded.isPresent(), "Flow should be persisted");
        assertEquals("test-code-verifier-abc123", reloaded.get().getCodeVerifier(),
                "PKCE code_verifier should be stored");
        assertNotNull(reloaded.get().getNonce(), "Nonce should be stored");

        oidcFlowRepository.delete(flow);
    }

    // ===== Test 11: Authorized Control Plane Admin =====

    @Test
    @DisplayName("11. User with correct admin claim is authorized as Control Plane Admin")
    void authorizedAdminClaimAccepted() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .claim("roles", List.of("netherite-admin", "other-role"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertTrue(oidcService.isControlPlaneAdmin(jwt, testIdpConfig),
                "User with correct admin claim should be authorized");
    }

    @Test
    @DisplayName("11b. Authorized user is created as an ordinary member, not an administrator")
    void authorizedUserGetsNoImplicitAuthority() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .claim("roles", List.of("netherite-admin"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        UserEntity user = oidcService.findOrCreateUser(jwt, testIdpConfig);
        assertNotNull(user);
        // WS6: successful IdP authentication admits a person to the
        // organization but grants NO authority. Becoming the organization's
        // authorized person is a separate, explicit designation.
        assertNotEquals(user.getId(), testOrg.getAuthorizedPersonUserId(),
                "SSO login must never auto-designate the organization's authorized person");
        assertEquals(testOrg.getId(), user.getOrganizationId(), "User should be in correct org");
        assertEquals("user-123", user.getOidcSubject(), "OIDC subject should be stored");
        assertEquals("https://test-issuer.example.com", user.getOidcIssuer(), "OIDC issuer should be stored");
        assertEquals("alice@oidc-test.example.com", user.getEmail());

        // Cleanup
        userRepository.delete(user);
    }

    // ===== Test 12: Authenticated but unauthorized user =====

    @Test
    @DisplayName("12a. User without admin claim is rejected")
    void unauthorizedUserRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .claim("roles", List.of("regular-user"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertFalse(oidcService.isControlPlaneAdmin(jwt, testIdpConfig),
                "User without admin claim should NOT be authorized");
    }

    @Test
    @DisplayName("12b. findOrCreateUser throws unauthorized for non-admin user")
    void findOrCreateUserThrowsForNonAdmin() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .claim("roles", List.of("regular-user"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.findOrCreateUser(jwt, testIdpConfig));
        assertTrue(ex.isUnauthorized(), "Should be unauthorized (403)");
        assertTrue(ex.getMessage().contains("not authorized"), "Error should indicate unauthorized");
    }

    @Test
    @DisplayName("12c. Missing admin claim entirely is rejected (fail closed)")
    void missingAdminClaimRejected() throws Exception {
        // No roles claim at all
        JWTClaimsSet claims = baseClaims().build();
        SignedJWT jwt = createSignedJwt(claims);

        assertFalse(oidcService.isControlPlaneAdmin(jwt, testIdpConfig),
                "Missing admin claim should fail closed");
    }

    @Test
    @DisplayName("12d. IdP config with no admin claim mapping fails closed")
    void noAdminClaimConfigFailsClosed() throws Exception {
        OrganizationIdpConfigEntity noClaimConfig = OrganizationIdpConfigEntity.builder()
                .organizationId(testOrg.getId())
                .issuerUrl("https://test-issuer.example.com")
                .clientId("test-client")
                .adminClaimName(null)
                .adminClaimValue(null)
                .status("ACTIVE")
                .build();

        JWTClaimsSet claims = baseClaims().build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.isControlPlaneAdmin(jwt, noClaimConfig));
        assertTrue(ex.isUnauthorized(), "Missing admin claim config should fail closed");
    }

    // ===== Nested claim extraction (provider neutrality) =====

    @Test
    @DisplayName("Nested claim path (realm_access.roles) is extracted correctly")
    void nestedClaimPathExtracted() throws Exception {
        OrganizationIdpConfigEntity nestedConfig = OrganizationIdpConfigEntity.builder()
                .organizationId(testOrg.getId())
                .issuerUrl("https://test-issuer.example.com")
                .clientId("test-client")
                .adminClaimName("realm_access.roles")
                .adminClaimValue("netherite-admin")
                .status("ACTIVE")
                .build();

        JWTClaimsSet claims = baseClaims()
                .claim("realm_access", Map.of("roles", List.of("netherite-admin", "user")))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertTrue(oidcService.isControlPlaneAdmin(jwt, nestedConfig),
                "Nested claim path should be extracted and validated");
    }

    @Test
    @DisplayName("Nested claim path with wrong value is rejected")
    void nestedClaimPathWrongValueRejected() throws Exception {
        OrganizationIdpConfigEntity nestedConfig = OrganizationIdpConfigEntity.builder()
                .organizationId(testOrg.getId())
                .issuerUrl("https://test-issuer.example.com")
                .clientId("test-client")
                .adminClaimName("realm_access.roles")
                .adminClaimValue("netherite-admin")
                .status("ACTIVE")
                .build();

        JWTClaimsSet claims = baseClaims()
                .claim("realm_access", Map.of("roles", List.of("regular-user")))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertFalse(oidcService.isControlPlaneAdmin(jwt, nestedConfig),
                "Nested claim with wrong value should be rejected");
    }

    // ===== Test 13: Cross-tenant identity rejection =====

    @Test
    @DisplayName("13a. Cross-tenant identity is rejected when user belongs to different org")
    void crossTenantIdentityRejected() throws Exception {
        // Create user in a DIFFERENT organization
        OrganizationEntity otherOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("Other Tenant Org")
                .slug("other-tenant-oidc")
                .status("ACTIVE")
                .build());

        UserEntity otherOrgUser = userRepository.save(UserEntity.builder()
                .organizationId(otherOrg.getId())
                .email("alice@oidc-test.example.com")
                .status("ACTIVE")
                .build());

        // OIDC token with admin claim but email matches user in DIFFERENT org
        JWTClaimsSet claims = baseClaims()
                .claim("roles", List.of("netherite-admin"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        // Should reject because email belongs to a different organization
        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.findOrCreateUser(jwt, testIdpConfig));
        assertTrue(ex.getMessage().contains("Cross-tenant"),
                "Should reject cross-tenant identity: " + ex.getMessage());

        // Cleanup
        userRepository.delete(otherOrgUser);
        organizationRepository.delete(otherOrg);
    }

    @Test
    @DisplayName("13b. Existing OIDC user in wrong org is rejected")
    void existingOidcUserInWrongOrgRejected() throws Exception {
        // Create user with matching OIDC identity but in DIFFERENT org
        OrganizationEntity otherOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("Wrong Org For OIDC")
                .slug("wrong-org-oidc")
                .status("ACTIVE")
                .build());

        UserEntity wrongOrgUser = userRepository.save(UserEntity.builder()
                .organizationId(otherOrg.getId())
                .email("someone@wrong.example.com")
                .oidcSubject("user-123")
                .oidcIssuer("https://test-issuer.example.com")
                .status("ACTIVE")
                .build());

        JWTClaimsSet claims = baseClaims()
                .claim("roles", List.of("netherite-admin"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.findOrCreateUser(jwt, testIdpConfig));
        assertTrue(ex.getMessage().contains("Cross-tenant"),
                "Should reject cross-tenant OIDC identity: " + ex.getMessage());

        // Cleanup
        userRepository.delete(wrongOrgUser);
        organizationRepository.delete(otherOrg);
    }

    // ===== Test 10c-e: azp enforcement for multi-audience tokens =====

    @Test
    @DisplayName("10c. Multi-audience ID token without azp is rejected")
    void multiAudienceWithoutAzpRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .audience(List.of("test-client", "other-client"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        null,
                        testIdpConfig));
        assertTrue(ex.getMessage().contains("azp"), "Error should mention azp: " + ex.getMessage());
    }

    @Test
    @DisplayName("10d. Multi-audience ID token with matching azp passes the azp check")
    void multiAudienceWithMatchingAzpPassesAzpCheck() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .audience(List.of("test-client", "other-client"))
                .claim("azp", "test-client")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        // Passes azp, then fails later at JWKS (no JWKS URI on the fixture config).
        // Mirrors the 7b pattern: assert the failure is NOT about azp.
        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        null,
                        testIdpConfig));
        assertFalse(ex.getMessage().contains("azp"),
                "Should NOT fail on azp — azp matches: " + ex.getMessage());
    }

    @Test
    @DisplayName("10e. Multi-audience ID token with wrong azp is rejected")
    void multiAudienceWithWrongAzpRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .audience(List.of("test-client", "other-client"))
                .claim("azp", "someone-elses-client")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.validateIdToken(
                        jwt.serialize(),
                        "https://test-issuer.example.com",
                        "test-client",
                        null,
                        testIdpConfig));
        assertTrue(ex.getMessage().contains("azp"), "Error should mention azp: " + ex.getMessage());
    }

    // ===== Test 14: verified-email enforcement =====

    @Test
    @DisplayName("14a. Unverified email is rejected even with a valid admin claim")
    void unverifiedEmailRejected() throws Exception {
        JWTClaimsSet claims = baseClaims()
                .claim("email_verified", false)
                .claim("roles", List.of("netherite-admin"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.findOrCreateUser(jwt, testIdpConfig));
        assertTrue(ex.isUnauthorized(), "Should be unauthorized (403)");
        assertTrue(ex.getMessage().contains("verified"), "Error should mention verification: " + ex.getMessage());
    }

    @Test
    @DisplayName("14b. Missing email_verified claim is rejected (fail closed)")
    void missingEmailVerifiedRejected() throws Exception {
        JWTClaimsSet withoutVerified = new JWTClaimsSet.Builder()
                .issuer("https://test-issuer.example.com")
                .subject("user-123")
                .audience("test-client")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("email", "alice@oidc-test.example.com")
                .claim("name", "Alice Test")
                .claim("roles", List.of("netherite-admin"))
                .build();
        SignedJWT jwt = createSignedJwt(withoutVerified);

        OidcService.OidcException ex = assertThrows(OidcService.OidcException.class, () ->
                oidcService.findOrCreateUser(jwt, testIdpConfig));
        assertTrue(ex.isUnauthorized(), "Should be unauthorized (403)");
    }

    @Test
    @DisplayName("14c. Unverified email never links to an existing same-org account")
    void unverifiedEmailNeverLinksAccount() throws Exception {
        UserEntity existing = userRepository.save(UserEntity.builder()
                .organizationId(testOrg.getId())
                .email("link-target@oidc-test.example.com")
                .status("ACTIVE")
                .build());

        JWTClaimsSet claims = baseClaims()
                .claim("email", "link-target@oidc-test.example.com")
                .claim("email_verified", false)
                .claim("roles", List.of("netherite-admin"))
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertThrows(OidcService.OidcException.class, () ->
                oidcService.findOrCreateUser(jwt, testIdpConfig));

        // The pre-existing account must be untouched: no identity linked.
        UserEntity reloaded = userRepository.findById(existing.getId()).orElseThrow();
        assertNull(reloaded.getOidcSubject(), "Unverified login must not link OIDC identity");

        userRepository.delete(existing);
    }

    // ===== Test 16: Session creation is covered in OwnerAuthenticationTest =====

    // ===== Admin claim with string value =====

    @Test
    @DisplayName("Admin claim as single string value is validated")
    void adminClaimStringValueValidated() throws Exception {
        OrganizationIdpConfigEntity stringClaimConfig = OrganizationIdpConfigEntity.builder()
                .organizationId(testOrg.getId())
                .issuerUrl("https://test-issuer.example.com")
                .clientId("test-client")
                .adminClaimName("department")
                .adminClaimValue("platform-engineering")
                .status("ACTIVE")
                .build();

        JWTClaimsSet claims = baseClaims()
                .claim("department", "platform-engineering")
                .build();
        SignedJWT jwt = createSignedJwt(claims);

        assertTrue(oidcService.isControlPlaneAdmin(jwt, stringClaimConfig));

        JWTClaimsSet wrongClaims = baseClaims()
                .claim("department", "marketing")
                .build();
        SignedJWT wrongJwt = createSignedJwt(wrongClaims);

        assertFalse(oidcService.isControlPlaneAdmin(wrongJwt, stringClaimConfig));
    }

    // ===== Full callback flow with expired state =====

    @Test
    @DisplayName("Expired flow is correctly marked as expired")
    void expiredFlowRejected() throws Exception {
        io.netherite.domain.entity.OidcFlowEntity expiredFlow =
                io.netherite.domain.entity.OidcFlowEntity.builder()
                        .state("expired-test-state")
                        .nonce("expired-nonce")
                        .codeVerifier("expired-verifier")
                        .organizationId(testOrg.getId())
                        .idpConfigId(testIdpConfig.getId())
                        .redirectUri("http://localhost:8080/v1/auth/oidc/callback")
                        .expiresAt(Instant.now().minusSeconds(60))
                        .build();

        assertTrue(expiredFlow.isExpired(), "Flow should be marked as expired");
    }
}
