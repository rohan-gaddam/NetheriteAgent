package io.netherite.auth.session;

import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.security.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the SessionCookieService security attributes
 * (WORKSTREAM 2 — human session cookie contract).
 *
 * <p>The cookie carries the RAW token: the SHA-256 digest is only ever persisted
 * by SessionCookieService.validateAndLoad / the auth controllers.</p>
 */
class SessionCookieServiceTest {

    private static final long TTL_OVERRIDE = 60 * 60;

    private SessionCookieService serviceWithSession() {
        return new SessionCookieService(mock(UserSessionRepository.class), new AuthProperties());
    }

    private MockHttpServletRequest httpRequest() {
        return new MockHttpServletRequest("GET", "/v1/auth");
    }

    private MockHttpServletRequest httpsRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth");
        request.setSecure(true);
        request.setScheme("https");
        return request;
    }

    @Test
    void createSessionCookie_defaultSecureAttributes() {
        ResponseCookie cookie = serviceWithSession().createSessionCookie("tok-1", httpRequest());
        assertEquals("neth_sid", cookie.getName());
        assertEquals("tok-1", cookie.getValue());
        assertTrue(cookie.isHttpOnly(), "HttpOnly must always be set");
        assertEquals("/", cookie.getPath());
        assertEquals(86400, cookie.getMaxAge().toSeconds());
        assertFalse(cookie.isSecure(), "Secure must be auto-derived: OFF for plain HTTP");
        assertEquals("Lax", cookie.getSameSite());
    }

    @Test
    void createSessionCookie_secureAutoOnForHttps() {
        ResponseCookie cookie = serviceWithSession().createSessionCookie("tok-2", httpsRequest());
        assertTrue(cookie.isSecure(), "Secure must be auto-derived: ON for HTTPS");
    }

    @Test
    void createSessionCookie_secureOverrideTrueWinsOnHttp() {
        AuthProperties props = new AuthProperties();
        props.getSession().setCookieSecure("true");
        SessionCookieService service = new SessionCookieService(mock(UserSessionRepository.class), props);

        ResponseCookie cookie = service.createSessionCookie("tok-3", httpRequest());
        assertTrue(cookie.isSecure(), "Explicit Secure override must win over HTTP derivation");
    }

    @Test
    void createSessionCookie_secureOverrideFalseWinsOnHttps() {
        AuthProperties props = new AuthProperties();
        props.getSession().setCookieSecure("false");
        SessionCookieService service = new SessionCookieService(mock(UserSessionRepository.class), props);

        ResponseCookie cookie = service.createSessionCookie("tok-4", httpsRequest());
        assertFalse(cookie.isSecure(), "Explicit Secure override must win over HTTPS derivation");
    }

    @Test
    void createSessionCookie_customNameAndTtlRespected() {
        AuthProperties props = new AuthProperties();
        props.getSession().setCookieName("sess");
        props.getSession().setTtlSeconds(TTL_OVERRIDE);
        SessionCookieService service = new SessionCookieService(mock(UserSessionRepository.class), props);

        ResponseCookie cookie = service.createSessionCookie("tok-5", httpRequest());
        assertEquals("sess", cookie.getName());
        assertEquals(TTL_OVERRIDE, cookie.getMaxAge().toSeconds());
    }

    @Test
    void clearSessionCookie_zeroesMaxAgeAndSameSite() {
        ResponseCookie cookie = serviceWithSession().clearSessionCookie();
        assertEquals("", cookie.getValue());
        assertEquals(0, cookie.getMaxAge().toSeconds());
        assertTrue(cookie.isHttpOnly());
        assertEquals("/", cookie.getPath());
        assertEquals("Lax", cookie.getSameSite());
    }

    @Test
    void validateAndLoad_digestsPresentedTokenBeforeLookup() throws Exception {
        UserSessionRepository repo = org.mockito.Mockito.mock(UserSessionRepository.class);
        SessionCookieService service = new SessionCookieService(repo, new AuthProperties());
        String presented = "raw-session-credential";

        service.validateAndLoad(presented);

        org.mockito.Mockito.verify(repo)
                .findBySessionToken(SessionTokenHasher.hash(presented));
        org.mockito.Mockito.verifyNoMoreInteractions(repo);
    }

    @Test
    void validateAndLoad_nullOrBlankReturnsNull() throws Exception {
        UserSessionRepository repo = org.mockito.Mockito.mock(UserSessionRepository.class);
        SessionCookieService service = new SessionCookieService(repo, new AuthProperties());

        org.junit.jupiter.api.Assertions.assertNull(service.validateAndLoad(null));
        org.junit.jupiter.api.Assertions.assertNull(service.validateAndLoad("   "));

        org.mockito.Mockito.verifyNoInteractions(repo);
    }
}