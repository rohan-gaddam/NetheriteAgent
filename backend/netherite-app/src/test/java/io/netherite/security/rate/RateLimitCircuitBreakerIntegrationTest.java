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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * After {@code circuit-break-failures} unresolved Redis calls the breaker opens and subsequent
 * security-sensitive requests stop touching the dependency entirely, still failing closed. This
 * lives in its own context (distinct property value) so its guard state cannot leak into other
 * suites.
 */
@SpringBootTest(properties = "netherite.rate-limit.dependency.circuit-break-failures=2")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "ratelimit"})
class RateLimitCircuitBreakerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;

    @MockBean private RateLimitStore rateLimitStore;

    private Cookie mintedSessionCookie() {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name("rl-cb-org")
                .slug("rl-cb-org-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        UserEntity user = userRepository.save(UserEntity.builder()
                .organizationId(org.getId())
                .email("rl-cb-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test123"))
                .fullName("Breaker Admin")
                .status("ACTIVE")
                .build());

        // WS6: designate so the session passes authorization and reaches the
        // rate-limit layer this test actually exercises.
        org.setAuthorizedPersonUserId(user.getId());
        organizationRepository.save(org);
        organizationRepository.flush();

        String rawToken = "rl-cb-session-" + UUID.randomUUID();
        userSessionRepository.save(UserSessionEntity.builder()
                .userId(user.getId())
                .organizationId(org.getId())
                .sessionToken(SessionTokenHasher.hash(rawToken))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());
        return new Cookie("neth_sid", rawToken);
    }

    @Test
    @DisplayName("After 2 failed Redis calls the breaker opens and requests stop hitting the store, still 503")
    void breakerStopsDependencyCallsAfterConsecutiveFailures() throws Exception {
        when(rateLimitStore.checkAndIncrement(anyList()))
                .thenThrow(new RuntimeException("redis down"));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/v1/policies")
                            .cookie(mintedSessionCookie())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"p\",\"description\":\"d\"}"))
                    .andExpect(status().isServiceUnavailable());
        }

        // Requests 1 and 2 tipped the breaker; request 3 was answered without contacting the store.
        verify(rateLimitStore, times(2)).checkAndIncrement(anyList());
    }
}