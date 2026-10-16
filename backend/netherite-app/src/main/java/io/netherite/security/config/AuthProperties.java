package io.netherite.security.config;

import io.netherite.auth.session.SessionCookieService;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "netherite.auth")
public class AuthProperties {

    private OwnerConfig owner = new OwnerConfig();
    private OidcConfig oidc = new OidcConfig();
    private SessionConfig session = new SessionConfig();

    @Data
    public static class OwnerConfig {
        private String email;
        private String passwordHash;
        private String organizationName = "NetheriteAgent";
        private String organizationSlug = "netherite";
    }

    @Data
    public static class OidcConfig {
        private boolean enabled = false;
        private String callbackBaseUrl = "http://localhost:3000";
        private int flowTimeoutSeconds = 300;
    }

    @Data
    public static class SessionConfig {
        /** HttpOnly cookie name carrying the server-side session credential. */
        private String cookieName = SessionCookieService.SESSION_COOKIE_NAME;
        /** Empty/blank = auto-derive from request scheme (Secure on HTTPS, not on HTTP). */
        private String cookieSecure = "";
        private String cookieSameSite = "Lax";
        private long ttlSeconds = 24 * 60 * 60;
    }
}
