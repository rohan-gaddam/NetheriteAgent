package io.netherite.auth.session;

import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.security.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Server-side session credential delivery exclusively via an HttpOnly cookie.
 *
 * Security contract (WORKSTREAM 2):
 *  - The session credential (user_sessions.session_token) never appears in
 *    URLs, query parameters, fragments, HTML, client-visible API responses,
 *    logs, telemetry, exception messages, or audit records.
 *  - The browser only ever holds it inside an HttpOnly cookie that JavaScript
 *    cannot read.
 *  - SameSite=Lax is the default: it preserves the OIDC top-level redirect
 *    back to the callback while blocking cross-site cookie sending otherwise.
 *  - Secure is derived from the request scheme (auto) unless explicitly
 *    overridden via configuration, so local HTTP development works without
 *    weakening production HTTPS deployments.
 */
@Component
@RequiredArgsConstructor
public class SessionCookieService {

    public static final String SESSION_COOKIE_NAME = "neth_sid";

    private final UserSessionRepository userSessionRepository;
    private final AuthProperties authProperties;

    public String cookieName() {
        String name = authProperties.getSession().getCookieName();
        return name == null || name.isBlank() ? SESSION_COOKIE_NAME : name;
    }

    public ResponseCookie createSessionCookie(String rawToken, HttpServletRequest request) {
        return ResponseCookie.from(cookieName(), rawToken)
                .httpOnly(true)
                .secure(resolveSecure(request))
                .sameSite(sameSite())
                .path("/")
                .maxAge(authProperties.getSession().getTtlSeconds())
                .build();
    }

    public ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(cookieName(), "")
                .httpOnly(true)
                .secure(false)
                .sameSite(sameSite())
                .path("/")
                .maxAge(0)
                .build();
    }

    /** Read the session credential out of the HttpOnly cookie (never any header). */
    public String readSessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = cookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Validate a session credential. Returns null (and cleans up) for
     * missing, unknown, or expired sessions. Callers reject such sessions.
     *
     * <p>The presented credential is digested before the repository lookup: the
     * {@code user_sessions.session_token} column stores {@code SHA-256(token)}, never the
     * plaintext credential.</p>
     */
    public UserSessionEntity validateAndLoad(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        UserSessionEntity session = userSessionRepository.findBySessionToken(SessionTokenHasher.hash(token)).orElse(null);
        if (session == null) {
            return null;
        }
        if (Instant.now().isAfter(session.getExpiresAt())) {
            userSessionRepository.delete(session);
            return null;
        }
        return session;
    }

    private String sameSite() {
        return authProperties.getSession().getCookieSameSite();
    }

    private boolean resolveSecure(HttpServletRequest request) {
        String override = authProperties.getSession().getCookieSecure();
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override);
        }
        return request.isSecure() || "https".equalsIgnoreCase(request.getScheme());
    }
}