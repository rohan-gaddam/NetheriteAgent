package io.netherite.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * netherite.auth.oidc.enabled is the master switch for SSO. When disabled the
 * entire SSO path fails closed — discovery, start, and callback all report
 * sso_disabled and never emulate or partially process authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "netherite.auth.oidc.enabled=false")
class OidcDisabledGateTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("E-1. Discovery reports sso_disabled when OIDC is administratively disabled")
    void discoverReportsSsoDisabled() throws Exception {
        mockMvc.perform(post("/v1/auth/oidc/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "alice@acme.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.errorCode").value("sso_disabled"));
    }

    @Test
    @DisplayName("E-2. OIDC start redirects to login with sso_disabled")
    void startRedirectsToSsoDisabled() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/start").param("email", "alice@acme.test"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=sso_disabled")));
    }

    @Test
    @DisplayName("E-3. OIDC callback is never processed when disabled")
    void callbackNeverProcessedWhenDisabled() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/callback")
                        .param("code", "not-a-real-code")
                        .param("state", "anything"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=sso_disabled")));
    }

    @Test
    @DisplayName("E-4. No session cookie is ever issued from the SSO path when disabled")
    void noSessionFromSsoPathWhenDisabled() throws Exception {
        mockMvc.perform(get("/v1/auth/oidc/start").param("email", "alice@acme.test"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("login?error=sso_disabled")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                        .doesNotExist("neth_sid"));
    }
}