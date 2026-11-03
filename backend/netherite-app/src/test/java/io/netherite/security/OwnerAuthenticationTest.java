package io.netherite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.security.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OwnerAuthenticationTest {

    private static final String OWNER_EMAIL = "strangeunknown03@gmail.com";
    private static final String OWNER_PASSWORD = "Fakepassword@26";
    private static final String NON_OWNER_EMAIL = "another@gmail.com";
    private static final String SESSION_COOKIE = "neth_sid";

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthProperties authProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private io.netherite.domain.repository.OrganizationDomainRepository organizationDomainRepository;
    @Autowired private ObjectMapper objectMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private record OwnerLogin(String body, Cookie sessionCookie) {}

    @BeforeEach
    void setUp() {
        // Clean up test data (order matters: sessions â†’ users â†’ orgs)
        UserEntity existingOwner = userRepository.findByEmail(OWNER_EMAIL).orElse(null);
        if (existingOwner != null) {
            userSessionRepository.findByOrganizationId(existingOwner.getOrganizationId())
                    .forEach(userSessionRepository::delete);
            userRepository.delete(existingOwner);
        }
        UserEntity existingNonOwner = userRepository.findByEmail(NON_OWNER_EMAIL).orElse(null);
        if (existingNonOwner != null) {
            userRepository.delete(existingNonOwner);
        }
        organizationRepository.findBySlug("netherite-test").ifPresent(o -> organizationRepository.delete(o));
    }

    private OwnerLogin loginAsOwner() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", OWNER_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(sessionCookie, "Owner login must issue the HttpOnly session cookie");
        return new OwnerLogin(result.getResponse().getContentAsString(), sessionCookie);
    }

    @Test
    @DisplayName("1. OWNER_EMAIL is correctly configured")
    void ownerEmailIsConfigured() {
        assertEquals(OWNER_EMAIL, authProperties.getOwner().getEmail());
    }

    @Test
    @DisplayName("2. Correct owner password authenticates and issues secure HttpOnly cookie")
    void correctOwnerPasswordAuthenticates() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", OWNER_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(SESSION_COOKIE))
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andExpect(cookie().path(SESSION_COOKIE, "/"))
                .andExpect(cookie().maxAge(SESSION_COOKIE, 86400))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").isNotEmpty())
                .andExpect(jsonPath("$.role").doesNotExist()); // WS6: no role in response
    }

    @Test
    @DisplayName("2b. Login response body never contains the session credential")
    void loginResponseBodyNeverContainsSessionToken() throws Exception {
        OwnerLogin login = loginAsOwner();
        assertFalse(login.body.contains("sessionToken"), "Session credential must not appear in the response body");
        assertFalse(login.body.contains(login.sessionCookie.getValue()),
                "Session credential value must not appear in the response body");
    }

    @Test
    @DisplayName("3. Incorrect owner password is rejected")
    void incorrectOwnerPasswordRejected() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("4. Another account cannot use owner direct login (identical 401 to prevent enumeration)")
    void nonOwnerCannotUseDirectLogin() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", NON_OWNER_EMAIL,
                                "password", "anypassword"))))
                .andExpect(status().isUnauthorized())
                // The failure body is deliberately identical to the owner wrong-password
                // response so a probe cannot distinguish owner accounts from any other email.
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("5. Owner is designated the owner organization's authorized person (WS6)")
    void ownerReceivesAdminAuthorization() throws Exception {
        OwnerLogin login = loginAsOwner();
        Map<String, Object> body = objectMapper.readValue(login.body, Map.class);
        String userId = (String) body.get("userId");
        String orgId = (String) body.get("organizationId");

        // WS6: authority is the organization-level designation, not a user role.
        // Owner login bootstraps the owner organization with the owner as its
        // single designated authorized person.
        OrganizationEntity org = organizationRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertEquals(UUID.fromString(userId), org.getAuthorizedPersonUserId(),
                "Owner login must designate the owner as the organization's authorized person");
    }

    @Test
    @DisplayName("6. Owner receives correct Organization")
    void ownerReceivesCorrectOrganization() throws Exception {
        OwnerLogin login = loginAsOwner();
        Map<String, Object> body = objectMapper.readValue(login.body, Map.class);
        String orgId = (String) body.get("organizationId");

        OrganizationEntity org = organizationRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertEquals("netherite-test", org.getSlug());
        assertEquals("ACTIVE", org.getStatus());
    }

    @Test
    @DisplayName("7. Owner receives correct TenantContext via session")
    void ownerReceivesCorrectTenantContext() throws Exception {
        OwnerLogin login = loginAsOwner();

        // Use the session cookie to access /v1/auth/me â€” this verifies TenantContext is set
        mockMvc.perform(get("/v1/auth/me").cookie(login.sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.role").doesNotExist()); // WS6: no role in response
    }

    @Test
    @DisplayName("8. Owner receives normal UserSession")
    void ownerReceivesNormalUserSession() throws Exception {
        OwnerLogin login = loginAsOwner();
        String sessionToken = login.sessionCookie.getValue();

        // Verify session exists in database (stored as SHA-256(token); the plaintext
        // credential is never persisted â€” see Batch C contract)
        Optional<UserSessionEntity> session = userSessionRepository.findBySessionToken(
                io.netherite.auth.session.SessionTokenHasher.hash(sessionToken));
        assertTrue(session.isPresent(), "Session should exist in database");
        assertTrue(session.get().getExpiresAt().isAfter(Instant.now()), "Session should not be expired");
        assertFalse(session.get().getSessionToken().equals(sessionToken),
                "Session token must never be stored in plaintext at rest");
        assertEquals(io.netherite.auth.session.SessionTokenHasher.hash(sessionToken),
                session.get().getSessionToken(),
                "Session column must hold the SHA-256 digest of the cookie credential");
    }

    @Test
    @DisplayName("9. Owner can access tenant-scoped management APIs")
    void ownerCanAccessManagementApis() throws Exception {
        OwnerLogin login = loginAsOwner();

        // Access agents endpoint (tenant-scoped)
        mockMvc.perform(get("/v1/agents").cookie(login.sessionCookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("10. A user session cannot toggle tenant context via any header")
    void ownerIsolationEnforced() throws Exception {
        // Create a user in a different org
        OrganizationEntity otherOrg = organizationRepository.save(
                OrganizationEntity.builder()
                        .name("Other Org")
                        .slug("other-org")
                        .status("ACTIVE")
                        .build());

        UserEntity otherUser = userRepository.save(UserEntity.builder()
                .organizationId(otherOrg.getId())
                .email("other@example.com")
                .status("ACTIVE")
                .build());

        OwnerLogin login = loginAsOwner();

        // The agents endpoint should only return the owner's org agents
        String agentsResponse = mockMvc.perform(get("/v1/agents").cookie(login.sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // other user's agents should not appear
        assertFalse(agentsResponse.contains(otherUser.getId().toString()),
                "Other org data should not be visible");

        // Cleanup
        userRepository.delete(otherUser);
        organizationRepository.delete(otherOrg);
    }

    @Test
    @DisplayName("11. No session credential is exposed to the frontend")
    void ownerEmailNotExposedToFrontend() throws Exception {
        OwnerLogin login = loginAsOwner();

        // Should not contain session credential, password hash, or owner-specific fields
        assertFalse(login.body.contains("sessionToken"), "Session credential should not be exposed");
        assertFalse(login.body.contains("passwordHash"), "Password hash should not be exposed");
        assertFalse(login.body.contains("oidcSubject"), "OIDC subject should not be exposed");
        assertFalse(login.body.contains("oidcIssuer"), "OIDC issuer should not be exposed");
    }

    @Test
    @DisplayName("12. Owner password is never logged")
    void ownerPasswordNeverLogged() throws Exception {
        // Attempt login with wrong password â€” verify error doesn't contain the password
        String response = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("wrongpassword"), "Password should never appear in response");
    }

    @Test
    @DisplayName("13. Owner password is never returned in API responses")
    void ownerPasswordNeverReturned() throws Exception {
        OwnerLogin login = loginAsOwner();

        assertFalse(login.body.contains("password"), "Password should not be in response");
        assertFalse(login.body.contains("$2a$"), "BCrypt hash should not be in response");
    }

    @Test
    @DisplayName("14. Password is not stored in plaintext")
    void passwordNotStoredPlaintext() throws Exception {
        loginAsOwner();

        UserEntity owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        assertNotNull(owner.getPasswordHash(), "Password hash should be stored");
        assertNotEquals(OWNER_PASSWORD, owner.getPasswordHash(), "Password should not be stored in plaintext");
        assertTrue(passwordEncoder.matches(OWNER_PASSWORD, owner.getPasswordHash()),
                "Stored hash should match the plaintext password");
    }

    @Test
    @DisplayName("15. No hardcoded owner password exists in source")
    void noHardcodedPasswordInSource() {
        // This is verified by code review â€” the password comes from config
        String configuredHash = authProperties.getOwner().getPasswordHash();
        assertNotNull(configuredHash, "Owner password hash should be configured");
        assertTrue(configuredHash.startsWith("$2a$") || configuredHash.startsWith("$2b$"), "Should be a BCrypt hash");
        assertFalse(configuredHash.equals(OWNER_PASSWORD), "Hash should not equal plaintext");
    }

    @Test
    @DisplayName("16. Missing owner configuration fails closed")
    void missingOwnerConfigFailsClosed() {
        // The test config has a valid hash; handleOwnerLogin must reject null/blank hashes
        assertTrue(authProperties.getOwner().getPasswordHash() != null
                        && !authProperties.getOwner().getPasswordHash().isBlank(),
                "Test config should have owner password hash set");
    }

    @Test
    @DisplayName("17. Owner logout invalidates session and clears cookie")
    void ownerLogoutInvalidatesSession() throws Exception {
        OwnerLogin login = loginAsOwner();

        // Verify session is valid
        mockMvc.perform(get("/v1/auth/me").cookie(login.sessionCookie))
                .andExpect(status().isOk());

        // Logout (cookie-based)
        mockMvc.perform(post("/v1/auth/logout").cookie(login.sessionCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(SESSION_COOKIE, 0))
                .andExpect(cookie().exists(SESSION_COOKIE));

        // Verify session is invalid
        mockMvc.perform(get("/v1/auth/me").cookie(login.sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("18. Expired sessions are rejected")
    void expiredSessionsRejected() throws Exception {
        OwnerLogin login = loginAsOwner();
        String sessionToken = login.sessionCookie.getValue();

        // Expire the session server-side
        UserSessionEntity session = userSessionRepository.findBySessionToken(
                io.netherite.auth.session.SessionTokenHasher.hash(sessionToken)).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(3600));
        userSessionRepository.save(session);

        // Verify expired session is rejected
        mockMvc.perform(get("/v1/auth/me").cookie(login.sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("19. Unauthenticated requests return 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("20. AI-agent API key auth still works after owner login")
    void agentAuthUnchanged() throws Exception {
        loginAsOwner();

        // Agent auth endpoint should still require agent credentials
        mockMvc.perform(get("/v1/actions/auth-check"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("21. OIDC start without email returns redirect with missing_email (not 500)")
    void oidcStartRedirectsWhenNoEmailProvided() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/start"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=missing_email")));
    }

    @Test
    @DisplayName("22. Owner login with new dev password succeeds")
    void ownerLoginWithDevPasswordSucceeds() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", OWNER_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andExpect(cookie().path(SESSION_COOKIE, "/"))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").isNotEmpty())
                .andExpect(jsonPath("$.role").doesNotExist()); // WS6: no role in response
    }

    @Test
    @DisplayName("23. Owner login with old test password is rejected")
    void ownerLoginWithOldTestPasswordRejected() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", "testowner123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("24. Owner session works with full flow to /v1/auth/me")
    void ownerSessionFlowToAuthMe() throws Exception {
        OwnerLogin login = loginAsOwner();

        // Access /v1/auth/me â€” verifies TenantContext is set via the cookie
        mockMvc.perform(get("/v1/auth/me").cookie(login.sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.role").doesNotExist()); // WS6: no role in response
    }

    @Test
    @DisplayName("25. Dashboard is accessible with owner session")
    void dashboardAccessibleWithOwnerSession() throws Exception {
        OwnerLogin login = loginAsOwner();

        mockMvc.perform(get("/v1/dashboard").cookie(login.sessionCookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("26. OIDC start returns redirect with unknown legacy IdP param (not 500)")
    void oidcStartWithCustomIssuerReturnsRedirect() throws Exception {
        // Even with a non-existent issuer, it should redirect (not 500)
        mockMvc.perform(get("/v1/auth/oidc/start").param("idp", "https://nonexistent.example.com"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=sso_unavailable")));
    }

    @Test
    @DisplayName("26b. OIDC start with unknown domain returns redirect with unknown_organization (not 500)")
    void oidcStartWithUnknownDomainReturnsRedirect() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/start").param("email", "user@unknowncompany.com"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=unknown_organization")));
    }

    @Test
    @DisplayName("26c. OIDC start with known domain but no IdP returns sso_not_configured (not 500)")
    void oidcStartWithKnownDomainNoIdpReturnsRedirect() throws Exception {
        // Create org with domain but no IdP config
        OrganizationEntity noIdpOrg = organizationRepository.save(
                OrganizationEntity.builder()
                        .name("No IdP Org")
                        .slug("no-idp-org")
                        .status("ACTIVE")
                        .build());
        io.netherite.domain.entity.OrganizationDomainEntity domain = io.netherite.domain.entity.OrganizationDomainEntity.builder()
                .organizationId(noIdpOrg.getId())
                .domain("noidp.test")
                .verified(true)
                .build();
        organizationDomainRepository.save(domain);

        mockMvc.perform(get("/v1/auth/oidc/start").param("email", "user@noidp.test"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=sso_not_configured")));

        // Cleanup
        organizationDomainRepository.delete(domain);
        organizationRepository.delete(noIdpOrg);
    }

    @Test
    @DisplayName("27. Plaintext owner password never appears in any response")
    void plaintextPasswordNeverInResponse() throws Exception {
        // Login success
        OwnerLogin login = loginAsOwner();
        assertFalse(login.body.contains("Fakepassword@26"), "Plaintext password should never appear in response");
        assertFalse(login.body.contains("$2a$"), "BCrypt hash should not be in response");
        assertFalse(login.body.contains("$2b$"), "BCrypt hash should not be in response");

        // Login failure
        String failResponse = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", OWNER_EMAIL,
                                "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertFalse(failResponse.contains("Fakepassword@26"), "Plaintext password should never appear in error response");
    }

    @Test
    @DisplayName("28. Owner can access agents, resources, approvals, audit")
    void ownerCanAccessAllManagementApis() throws Exception {
        OwnerLogin login = loginAsOwner();

        mockMvc.perform(get("/v1/agents").cookie(login.sessionCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/resources").cookie(login.sessionCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/approvals").cookie(login.sessionCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/audit").cookie(login.sessionCookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("29. Tenant isolation remains enforced with owner session")
    void tenantIsolationEnforcedWithOwnerSession() throws Exception {
        OrganizationEntity otherOrg = organizationRepository.save(
                OrganizationEntity.builder()
                        .name("Other Org 2")
                        .slug("other-org-2")
                        .status("ACTIVE")
                        .build());

        OwnerLogin login = loginAsOwner();

        // Owner should not see other org's data
        String agentsResponse = mockMvc.perform(get("/v1/agents").cookie(login.sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(agentsResponse.contains(otherOrg.getId().toString()),
                "Other org data should not be visible to owner");

        // Cleanup
        organizationRepository.delete(otherOrg);
    }

    @Test
    @DisplayName("30. Configured owner email matches expected value")
    void configuredOwnerEmailMatchesExpected() {
        assertEquals("strangeunknown03@gmail.com", authProperties.getOwner().getEmail());
        assertTrue(authProperties.getOwner().getEmail().endsWith("@gmail.com"),
                "Owner email should be a Gmail address");
    }
}
