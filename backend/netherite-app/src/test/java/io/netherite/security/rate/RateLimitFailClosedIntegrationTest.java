package io.netherite.security.rate;

import io.netherite.auth.session.SessionTokenHasher;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.entity.UserSessionEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed behavior when the distributed dependency is down. The real store is swapped for a
 * throwing stub so every {@code checkAndIncrement} simulates an unreachable Redis; sessions are
 * minted directly so no part of the assertion path depends on login itself. Both scenarios hold
 * regardless of breaker state, which keeps them order-independent in the shared context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "ratelimit"})
class RateLimitFailClosedIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;

    @MockBean private RateLimitStore rateLimitStore;

    private Cookie mintedSessionCookie() {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name("rl-fc-org")
                .slug("rl-fc-org-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email("rl-fc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("Fail Closed Admin")
                .status("ACTIVE")
                .build());

        // WS6: designate so the session is authorized to reach /v1/policies and
        // /v1/audit. These tests assert rate-limit fail-closed behaviour, which
        // must trigger after authorization succeeds.
        org.setAuthorizedPersonUserId(user.getId());
        organizationRepository.save(org);
        organizationRepository.flush();

        String rawToken = "rl-fc-session-" + UUID.randomUUID();
        userSessionRepository.save(UserSessionEntity.builder()
                .userId(user.getId())
                .organizationId(org.getId())
                .sessionToken(SessionTokenHasher.hash(rawToken))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());
        return new Cookie("neth_sid", rawToken);
    }

    @Test
    @DisplayName("Security-sensitive POST is 503 (never grade-into-allow) when Redis cannot decide")
    void failClosedRouteReturns503WhenRedisIsDown() throws Exception {
        when(rateLimitStore.checkAndIncrement(anyList()))
                .thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(post("/v1/policies")
                        .cookie(mintedSessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p\",\"description\":\"d\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ERR_SYSTEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    @DisplayName("Read-only surface stays available (fail-open) when Redis cannot decide")
    void failOpenRoutePassesWhenRedisIsDown() throws Exception {
        when(rateLimitStore.checkAndIncrement(anyList()))
                .thenThrow(new RuntimeException("redis down"));

        // Audit reads are classified fail-open: a Redis outage must not blackout read surfaces.
        mockMvc.perform(get("/v1/audit")
                        .cookie(mintedSessionCookie()))
                .andExpect(status().isOk());

        // The dependency was still exercised for the fail-open surface.
        verify(rateLimitStore).checkAndIncrement(anyList());
    }
}