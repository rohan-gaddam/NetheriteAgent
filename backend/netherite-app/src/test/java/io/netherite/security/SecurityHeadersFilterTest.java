package io.netherite.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch F — cache-control on the application surfaces.
 *
 * <p>{@code SecurityHeadersFilter} (order 0) disables caching on /v1 and
 * /actuator so session-bearing and management payloads are never persisted by a
 * browser or intermediary. The transport security headers (HSTS/CSP/nosniff/…)
 * are owned by the single untrusted entry point — the Caddy edge — and are
 * asserted against the live edge in the Batch F panel, not here (avoiding
 * double emission when the app sits behind a proxy that already stamps them).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersFilterTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("BatchF-B1. API responses are never cached (no-store)")
    void testApiNoStore() throws Exception {
        mockMvc.perform(get("/v1/resources"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("BatchF-B2. Actuator responses are never cached (no-store)")
    void testActuatorNoStore() throws Exception {
        // Probes are public; the status body must still never be cached.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("BatchF-B3. The no-store guard is present on every /v1 rejection (order 0)")
    void testNoStorePresentOnRejection() throws Exception {
        mockMvc.perform(get("/v1/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}