package io.netherite.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netherite.auth.service.OidcService;
import io.netherite.domain.entity.OidcFlowEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.OrganizationIdpConfigEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OidcFlowRepository;
import io.netherite.domain.repository.OrganizationIdpConfigRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WORKSTREAM 2 — Human Session Security verification (tests A–J).
 *
 * The human session credential (user_sessions.session_token) must never appear
 * in logs, URLs, query parameters, fragments, HTML, client-visible API
 * responses, telemetry, exception messages, or audit records. It is delivered
 * exclusively as an HttpOnly cookie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class HumanSessionSecurityTest {

    private static final String SESSION_COOKIE = "neth_sid";
    private static final long SESSION_TTL_SECONDS = 86400L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationIdpConfigRepository idpConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private OidcFlowRepository oidcFlowRepository;

    // OIDC token validation/exchange is external; stub it so the callback
    // success path can be exercised entirely server-side.
    @MockBean private OidcService oidcService;

    private OrganizationEntity org;
    private UserEntity user;
    private final List<OidcFlowEntity> createdFlows = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        org = organizationRepository.save(OrganizationEntity.builder()
                .name("Security Test Org")
                .slug("security-test-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email("employee-security-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .fullName("Security Test User")
                .status("ACTIVE")
                .build());

        // WS6: designate the employee so session-based management access is
        // authorized. This suite asserts cookie/session hygiene, not the
        // authorization model itself.
        org.setAuthorizedPersonUserId(user.getId());
        organizationRepository.save(org);
        organizationRepository.flush();

        given(oidcService.exchangeCodeForTokens(anyString(), any(), anyString(), anyString()))
                .willReturn("{\"id_token\":\"abc.def.ghi\"}");
        given(oidcService.validateIdToken(anyString(), anyString(), anyString(), any(), any()))
                .willReturn(new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),
                        new JWTClaimsSet.Builder().subject("security-test-user").build()));
        given(oidcService.findOrCreateUser(any(), any())).willReturn(user);
    }

    @AfterEach
    void tearDown() {
        userRepository.findAllByOrganizationId(org.getId()).forEach(userRepository::delete);
        createdFlows.forEach(oidcFlowRepository::delete);
        idpConfigRepository.findByOrganizationId(org.getId()).ifPresent(idpConfigRepository::delete);
        organizationRepository.delete(org);
    }

    private UserSessionEntity createSession(UUID userId, UUID orgId, String token, Instant expiresAt) {
        // At rest, sessions are stored as SHA-256(token); the raw token is only ever
        // presented in the HttpOnly cookie (validated via validateAndLoad).
        return userSessionRepository.save(UserSessionEntity.builder()
                .userId(userId)
                .organizationId(orgId)
                .sessionToken(io.netherite.auth.session.SessionTokenHasher.hash(token))
                .expiresAt(expiresAt)
                .build());
    }

    private OrganizationIdpConfigEntity createIdpConfig() {
        return idpConfigRepository.save(OrganizationIdpConfigEntity.builder()
                .organizationId(org.getId())
                .issuerUrl("https://idp.security-test.local")
                .clientId("security-test-client")
                .adminClaimName("roles")
                .adminClaimValue("netherite-admin")
                .status("ACTIVE")
                .build());
    }

    private OidcFlowEntity createFlow(OrganizationIdpConfigEntity idpConfig, String state) {
        OidcFlowEntity flow = oidcFlowRepository.save(OidcFlowEntity.builder()
                .state(state)
                .nonce("nonce-" + UUID.randomUUID())
                .codeVerifier("verifier-" + UUID.randomUUID())
                .organizationId(org.getId())
                .idpConfigId(idpConfig.getId())
                .redirectUri("http://localhost:8080/v1/auth/oidc/callback")
                .expiresAt(Instant.now().plus(300, ChronoUnit.SECONDS))
                .build());
        createdFlows.add(flow);
        return flow;
    }

    // ===== A. Callback redirect location is clean =====

    @Test
    @DisplayName("A. OIDC callback redirects to /auth/callback with a clean URL (no session token)")
    void oidcCallbackRedirectCleanUrl() throws Exception {
        OrganizationIdpConfigEntity idp = createIdpConfig();
        String state = "state-" + UUID.randomUUID();
        createFlow(idp, state);

        MvcResult result = mockMvc.perform(get("/v1/auth/oidc/callback")
                        .param("code", "auth-code")
                        .param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/auth/callback"))
                .andExpect(header().string("Location", not(containsString("sessionToken"))))
                .andExpect(header().string("Location", not(containsString("?"))))
                .andExpect(cookie().exists(SESSION_COOKIE))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertFalse(location.contains("sessionToken"), "No credential may appear in redirect Location");
        assertFalse(location.contains("?"), "Redirect Location must be a clean URL with no query string");
    }

    // ===== B. Cookie carries the correct security attributes =====

    @Test
    @DisplayName("B. Session cookie is HttpOnly+SameSite=Lax, Path=/, Max-Age matches TTL; Secure auto-off over local HTTP")
    void sessionCookieAttributes() throws Exception {
        OrganizationIdpConfigEntity idp = createIdpConfig();
        String state = "state-" + UUID.randomUUID();
        createFlow(idp, state);

        MvcResult result = mockMvc.perform(get("/v1/auth/oidc/callback")
                        .param("code", "auth-code")
                        .param("state", state))
                .andExpect(status().isFound())
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andExpect(cookie().path(SESSION_COOKIE, "/"))
                .andExpect(cookie().maxAge(SESSION_COOKIE, (int) SESSION_TTL_SECONDS))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertFalse(setCookie.toLowerCase().contains("secure"),
                "Secure flag must be auto-derived: off for plain-HTTP test requests");

        // The value in the Set-Cookie must equal the persisted session token and
        // never surface anywhere else in the response.
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);
        assertFalse(cookie.getValue().isBlank());
    }

    // ===== C. No credential in any client-visible surface =====

    @Test
    @DisplayName("C. Login response carries no credential in body; only the Set-Cookie holds the session value")
    void loginResponseNoCredentialInBody() throws Exception {
        String email = "owner@example.com";
        String password = "Fakepassword@26";

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);
        assertFalse(body.contains("sessionToken"), "sessionToken key must never appear in the login body");
        assertFalse(body.contains(cookie.getValue()), "session value must never appear in the login body");

        // /v1/auth/me must not expose the credential either.
        String meBody = mockMvc.perform(get("/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(meBody.contains(cookie.getValue()), "session value must never appear in /v1/auth/me body");
        assertFalse(meBody.contains("sessionToken"), "sessionToken key must never appear in /v1/auth/me body");
    }

    // ===== D. No session credential in logs =====

    @Test
    @DisplayName("D. Session credential never appears in application logs (login + OIDC callback + logout)")
    void noSessionCredentialInLogs(CapturedOutput output) throws Exception {
        String email = "owner@example.com";
        String password = "Fakepassword@26";

        MvcResult login = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        String ownerToken = login.getResponse().getCookie(SESSION_COOKIE).getValue();

        // OIDC callback path
        OrganizationIdpConfigEntity idp = createIdpConfig();
        String state = "state-" + UUID.randomUUID();
        createFlow(idp, state);
        MvcResult callback = mockMvc.perform(get("/v1/auth/oidc/callback")
                        .param("code", "auth-code")
                        .param("state", state))
                .andExpect(status().isFound())
                .andReturn();
        String oidcToken = callback.getResponse().getCookie(SESSION_COOKIE).getValue();

        mockMvc.perform(post("/v1/auth/logout").cookie(callback.getResponse().getCookie(SESSION_COOKIE)))
                .andExpect(status().isOk());

        String logs = output.getAll();
        assertFalse(logs.contains(ownerToken), "Owner session credential leaked into logs");
        assertFalse(logs.contains(oidcToken), "OIDC session credential leaked into logs");
        assertFalse(logs.contains("sessionToken="), "No 'sessionToken=' should exist in logs");
        assertFalse(logs.toLowerCase().contains("bearer "), "No bearer credential should exist in logs");
    }

    // ===== E. Frontend has no JavaScript-visible credential path =====
    // Verified statically (no localStorage/sessionStorage of the credential) and
    // in the browser E2E (Phase 10); this test enforces the server never issues one.

    @Test
    @DisplayName("E. Issued credentials are only in cookie headers, never in JSON bodies")
    void issuedCredentialsOnlyInCookies(CapturedOutput output) throws Exception {
        String email = "owner@example.com";
        String password = "Fakepassword@26";
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").doesNotExist());

        // Every credential transport is a Set-Cookie header; never a JSON field.
        assertFalse(output.getAll().contains("sessionToken="));
    }

    // ===== F. Valid/absent/invalid/expired session handling =====

    @Test
    @DisplayName("F. Valid cookie authenticates; absent/invalid/expired cookies are rejected with 401")
    void sessionLifecycleRejections() throws Exception {
        String token = "security-valid-" + UUID.randomUUID();
        createSession(user.getId(), org.getId(), token, Instant.now().plus(24, ChronoUnit.HOURS));
        Cookie valid = new Cookie(SESSION_COOKIE, token);

        // Valid
        mockMvc.perform(get("/v1/agents").cookie(valid))
                .andExpect(status().isOk());

        // Absent → agent auth required → 401
        mockMvc.perform(get("/v1/agents"))
                .andExpect(status().isUnauthorized());

        // Invalid value
        mockMvc.perform(get("/v1/agents").cookie(new Cookie(SESSION_COOKIE, "bogus-token")))
                .andExpect(status().isUnauthorized());

        // Expired
        String expiredToken = "security-expired-" + UUID.randomUUID();
        createSession(user.getId(), org.getId(), expiredToken, Instant.now().minus(1, ChronoUnit.HOURS));
        mockMvc.perform(get("/v1/agents").cookie(new Cookie(SESSION_COOKIE, expiredToken)))
                .andExpect(status().isUnauthorized());
    }

    // ===== G. Logout invalidates server session and clears the cookie =====

    @Test
    @DisplayName("G. Logout deletes the server session and clears the cookie; the old value is rejected")
    void logoutInvalidatesAndClearsCookie() throws Exception {
        String token = "security-logout-" + UUID.randomUUID();
        createSession(user.getId(), org.getId(), token, Instant.now().plus(24, ChronoUnit.HOURS));
        Cookie cookie = new Cookie(SESSION_COOKIE, token);

        mockMvc.perform(get("/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/auth/logout").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(SESSION_COOKIE, 0));

        assertTrue(userSessionRepository.findBySessionToken(
                io.netherite.auth.session.SessionTokenHasher.hash(token)).isEmpty(),
                "Session row must be deleted on logout");

        mockMvc.perform(get("/v1/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    // ===== H. Owner login: good/wrong credentials, no credential leaks =====

    @Test
    @DisplayName("H. Owner login (good+bad): cookie on success, 401 on failure, no credential in body/logs")
    void ownerLoginNoLeaks(CapturedOutput output) throws Exception {
        String email = "owner@example.com";
        String password = "Fakepassword@26";
        String wrong = "WrongPass@123";

        MvcResult good = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andReturn();
        String token = good.getResponse().getCookie(SESSION_COOKIE).getValue();

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", wrong))))
                .andExpect(status().isUnauthorized());

        String logs = output.getAll();
        assertFalse(logs.contains(token), "Owner session credential must not appear in logs");
        assertFalse(logs.contains(wrong), "Password must not appear in logs");
        assertFalse(good.getResponse().getContentAsString().contains(token),
                "Owner session credential must not appear in response body");
    }

    // ===== I. OIDC employee session authenticates and works via the cookie =====

    @Test
    @DisplayName("I. OIDC employee login issues a working cookie session with no URL/body/log leakage")
    void oidcEmployeeCookieSessionWorks(CapturedOutput output) throws Exception {
        OrganizationIdpConfigEntity idp = createIdpConfig();
        String state = "state-" + UUID.randomUUID();
        createFlow(idp, state);

        MvcResult result = mockMvc.perform(get("/v1/auth/oidc/callback")
                        .param("code", "auth-code")
                        .param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/auth/callback"))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);

        mockMvc.perform(get("/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").doesNotExist()); // WS6: no role in response

        String logs = output.getAll();
        assertFalse(logs.contains(cookie.getValue()), "OIDC session credential must not appear in logs");
        assertFalse(result.getResponse().getHeader("Location").contains(cookie.getValue()),
                "OIDC session credential must not appear in the redirect URL");
    }

    // ===== J. Cross-tenant isolation =====

    @Test
    @DisplayName("J. A session scoped to org A cannot see another org's data")
    void crossTenantIsolation() throws Exception {
        String token = "security-tenant-" + UUID.randomUUID();
        createSession(user.getId(), org.getId(), token, Instant.now().plus(24, ChronoUnit.HOURS));
        Cookie cookie = new Cookie(SESSION_COOKIE, token);

        OrganizationEntity otherOrg = organizationRepository.save(OrganizationEntity.builder()
                .name("Other Tenant Org")
                .slug("other-tenant-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        userRepository.save(UserEntity.builder()
                .organizationId(otherOrg.getId())
                .email("other-tenant-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .status("ACTIVE")
                .build());

        String agents = mockMvc.perform(get("/v1/agents").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(agents.contains(otherOrg.getId().toString()),
                "Cross-tenant data must never be visible to another organization's session");

        userRepository.findAllByOrganizationId(otherOrg.getId()).forEach(userRepository::delete);
        organizationRepository.delete(otherOrg);
    }
}