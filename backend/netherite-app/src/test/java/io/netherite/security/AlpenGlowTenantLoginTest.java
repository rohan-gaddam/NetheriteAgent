package io.netherite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.auth.session.SessionTokenHasher;
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

/**
 * AlpenGlow Enterprise tenant login verification.
 *
 * Verifies that a password-based login for a tenant user (not the deployment
 * owner) establishes the correct tenant context, that invalid credentials are
 * rejected, and that the tenant identity cannot be altered by client-supplied
 * parameters. Follows the existing WS6 model: one designated authorized person
 * per organization, no RBAC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlpenGlowTenantLoginTest {

    private static final String ALPENGLOW_EMAIL = "admin@alpenglow.example.com";
    private static final String ALPENGLOW_PASSWORD = "Fakepassword@26";
    private static final String ALPENGLOW_ORG_SLUG = "alpenglow";
    private static final String ALPENGLOW_ORG_NAME = "AlpenGlow Enterprise";
    private static final String SESSION_COOKIE = "neth_sid";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthProperties authProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserSessionRepository userSessionRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private record AlpenglowLogin(String body, Cookie sessionCookie) {}

    @BeforeEach
    void setUp() {
        // Clean up any prior Alpenglow test data (sessions → users → orgs)
        UserEntity existing = userRepository.findByEmail(ALPENGLOW_EMAIL).orElse(null);
        if (existing != null) {
            userSessionRepository.findByOrganizationId(existing.getOrganizationId())
                    .forEach(userSessionRepository::delete);
            userRepository.delete(existing);
        }
        organizationRepository.findBySlug(ALPENGLOW_ORG_SLUG).ifPresent(organizationRepository::delete);
    }

    @Test
    @DisplayName("1. Valid Alpenglow credentials establish the correct tenant context")
    void validAlpenGlowCredentialsEstablishTenantContext() throws Exception {
        // Provision the Alpenglow tenant in the database (mirrors V15 migration data).
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());

        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .fullName("AlpenGlow Administrator")
                .status("ACTIVE")
                .build());

        // Designate the Alpenglow admin as the organization's authorized person (WS6).
        org.setAuthorizedPersonUserId(user.getId());
        organizationRepository.save(org);

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", ALPENGLOW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(SESSION_COOKIE))
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andExpect(cookie().path(SESSION_COOKIE, "/"))
                .andExpect(cookie().maxAge(SESSION_COOKIE, 86400))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").isNotEmpty())
                .andExpect(jsonPath("$.role").doesNotExist()) // WS6: no role in response
                .andReturn();

        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(sessionCookie, "AlpenGlow login must issue the HttpOnly session cookie");

        // Verify the tenant context matches the provisioned organization (not client-supplied).
        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals(org.getId().toString(), body.get("organizationId"),
                "Tenant context must be the Alpenglow organization from the database record");
        assertEquals(user.getId().toString(), body.get("userId"),
                "User id must match the authenticated Alpenglow user");

        // /v1/auth/me reflects the same tenant context.
        mockMvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ALPENGLOW_EMAIL))
                .andExpect(jsonPath("$.organizationId").value(org.getId().toString()))
                .andExpect(jsonPath("$.role").doesNotExist()); // WS6: no role

        // Cleanup
        userSessionRepository.findByOrganizationId(org.getId()).forEach(userSessionRepository::delete);
        userRepository.delete(user);
        organizationRepository.delete(org);
    }

    @Test
    @DisplayName("2. Invalid Alpenglow password is rejected with uniform 401")
    void invalidAlpenGlowPasswordRejected() throws Exception {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());
        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .status("ACTIVE")
                .build());

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        // Cleanup
        userRepository.delete(user);
        organizationRepository.delete(org);
    }

    @Test
    @DisplayName("3. Unknown Alpenglow email is rejected with identical 401 (no enumeration)")
    void unknownAlpenGlowEmailRejected() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "nonexistent@alpenglow.example.com",
                                "password", "anypassword"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ERR_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("4. Tenant identity cannot be changed by client-supplied parameters")
    void tenantIdentityCannotBeChangedByClient() throws Exception {
        // Provision Alpenglow tenant.
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());
        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .status("ACTIVE")
                .build());

        // Provision a second organization to attempt a cross-tenant escape.
        OrganizationEntity otherOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("Other Org")
                .slug("other-org")
                .status("ACTIVE")
                .build());

        // Attempt login with Alpenglow credentials but try to inject the other org's id.
        // The server ignores any client-supplied organization id; the tenant comes from
        // the user's database record only.
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", ALPENGLOW_PASSWORD,
                                "organizationId", otherOrg.getId().toString()))))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals(org.getId().toString(), body.get("organizationId"),
                "Client-supplied organizationId must be ignored; tenant comes from user record");
        assertNotEquals(otherOrg.getId().toString(), body.get("organizationId"),
                "Login must not establish the client-supplied (other) organization as tenant");

        // Verify /v1/auth/me reflects the Alpenglow org, not the injected one.
        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);
        mockMvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(org.getId().toString()))
                .andExpect(jsonPath("$.organizationId").value(not(otherOrg.getId().toString())));

        // Cleanup
        userSessionRepository.findByOrganizationId(org.getId()).forEach(userSessionRepository::delete);
        userRepository.delete(user);
        organizationRepository.delete(org);
        organizationRepository.delete(otherOrg);
    }

    @Test
    @DisplayName("5. Session credential is stored as SHA-256 hash, never plaintext")
    void sessionStoredAsHashNotPlaintext() throws Exception {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());
        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .status("ACTIVE")
                .build());

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", ALPENGLOW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(sessionCookie);

        Optional<UserSessionEntity> session = userSessionRepository.findBySessionToken(
                SessionTokenHasher.hash(sessionCookie.getValue()));
        assertTrue(session.isPresent(), "Session should exist in database");
        assertNotEquals(sessionCookie.getValue(), session.get().getSessionToken(),
                "Session token must never be stored in plaintext at rest");
        assertEquals(SessionTokenHasher.hash(sessionCookie.getValue()),
                session.get().getSessionToken(),
                "Session column must hold the SHA-256 digest of the cookie credential");

        // Cleanup
        userSessionRepository.delete(session.get());
        userRepository.delete(user);
        organizationRepository.delete(org);
    }

    @Test
    @DisplayName("6. No session credential or password hash is exposed in the response body")
    void noCredentialOrHashInResponseBody() throws Exception {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());
        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .status("ACTIVE")
                .build());

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", ALPENGLOW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);
        String body = result.getResponse().getContentAsString();

        assertFalse(body.contains("sessionToken"), "sessionToken key must not appear in login body");
        assertFalse(body.contains(sessionCookie.getValue()), "session value must not appear in login body");
        assertFalse(body.contains("passwordHash"), "passwordHash must not appear in login body");
        assertFalse(body.contains(ALPENGLOW_PASSWORD), "Plaintext password must not appear in login body");
        assertFalse(body.contains("$2a$") && body.contains("$2b$"),
                "BCrypt hash must not appear in login body");

        // /v1/auth/me must not expose the credential either.
        String meBody = mockMvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(meBody.contains(sessionCookie.getValue()),
                "Session value must not appear in /v1/auth/me body");

        // Cleanup
        userSessionRepository.findByOrganizationId(org.getId()).forEach(userSessionRepository::delete);
        userRepository.delete(user);
        organizationRepository.delete(org);
    }

    @Test
    @DisplayName("7. Alpenglow user is the organization's designated authorized person (WS6, no RBAC)")
    void alpenglowUserIsDesignatedAuthorizedPerson() throws Exception {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());
        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .status("ACTIVE")
                .build());
        org.setAuthorizedPersonUserId(user.getId());
        organizationRepository.save(org);

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", ALPENGLOW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);

        // The Alpenglow user, as the designated authorized person, can access
        // tenant-scoped management APIs (agents, resources, approvals, audit).
        mockMvc.perform(get("/v1/agents").cookie(sessionCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/resources").cookie(sessionCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/approvals").cookie(sessionCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/audit").cookie(sessionCookie)).andExpect(status().isOk());

        // WS6: there is no role field anywhere -- authority is the single
        // designated authorized person on the organization row.
        String meBody = mockMvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(meBody.contains("\"role\""), "No role field in response (WS6: no RBAC)");

        // Cleanup
        userSessionRepository.findByOrganizationId(org.getId()).forEach(userSessionRepository::delete);
        userRepository.delete(user);
        organizationRepository.delete(org);
    }

    @Test
    @DisplayName("8. Expired Alpenglow session is rejected")
    void expiredAlpenGlowSessionRejected() throws Exception {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name(ALPENGLOW_ORG_NAME)
                .slug(ALPENGLOW_ORG_SLUG)
                .status("ACTIVE")
                .build());
        String passwordHash = passwordEncoder.encode(ALPENGLOW_PASSWORD);
        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email(ALPENGLOW_EMAIL)
                .passwordHash(passwordHash)
                .status("ACTIVE")
                .build());

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ALPENGLOW_EMAIL,
                                "password", ALPENGLOW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);
        String sessionToken = sessionCookie.getValue();

        // Expire the session server-side.
        UserSessionEntity session = userSessionRepository.findBySessionToken(
                SessionTokenHasher.hash(sessionToken)).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(3600));
        userSessionRepository.save(session);

        mockMvc.perform(get("/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());

        // Cleanup
        userSessionRepository.delete(session);
        userRepository.delete(user);
        organizationRepository.delete(org);
    }

    @Test
    @DisplayName("9. Unauthenticated requests to tenant-scoped APIs return 401")
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/v1/agents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/resources")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("10. Owner login still works alongside Alpenglow tenant login (no regression)")
    void ownerLoginStillWorks() throws Exception {
        String ownerEmail = authProperties.getOwner().getEmail();
        String ownerPassword = ALPENGLOW_PASSWORD; // test hash matches this password

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ownerEmail,
                                "password", ownerPassword))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(SESSION_COOKIE))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").isNotEmpty())
                .andReturn();

        Cookie sessionCookie = result.getResponse().getCookie(SESSION_COOKIE);

        // Owner session can access management APIs.
        mockMvc.perform(get("/v1/agents").cookie(sessionCookie)).andExpect(status().isOk());

        // Cleanup: owner test data is cleaned up by OwnerAuthenticationTest.setUp,
        // but we remove any sessions we created here to avoid cross-test leakage.
        userSessionRepository.findByOrganizationId(UUID.fromString(
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class)
                        .get("organizationId").toString())).forEach(userSessionRepository::delete);
    }
}
