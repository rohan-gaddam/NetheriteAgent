package io.netherite.security.rate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-authentication tier ({@code @Order(0)}) verification: floods on login, OIDC and agent
 * surfaces are blocked with 429 before any credential verification or database work runs, all
 * dimensions use disjoint buckets so attackers picking new IPs/accounts cannot bypass, and a
 * window expiry releases traffic again. Overrides the IP per request via the MockMvc customizer
 * because MockMvc defaults every request to 127.0.0.1 (trusted-proxy rewriting is a container
 * concern, tested in the live-Docker phase).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "ratelimit"})
class PreAuthRateLimitFilterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private MockHttpServletRequestBuilder login(String ip, String email, String password) throws Exception {
        return fromIp(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))), ip);
    }

    @Test
    @DisplayName("Flood from one source IP across many accounts is denied after the IP budget")
    void sameIpFloodAcrossAccountsIsDenied() throws Exception {
        String ip = "203.0.113.10";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(login(ip, "flood-" + i + "@example.com", "wrong"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(ip, "flood-3@example.com", "wrong"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("ERR_RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    @DisplayName("Credential-stuffing across many IPs is stopped by the hashed account bucket")
    void accountFloodAcrossIpsIsDenied() throws Exception {
        String[] ips = {"203.0.113.11", "203.0.113.12", "203.0.113.13", "203.0.113.14", "203.0.113.15"};
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(login(ips[i], "strangeunknown03@gmail.com", "Fakepassword@26"))
                    .andExpect(status().isOk());
        }
        // Fifth source, same account -> account bucket (limit 4) denies even though this IP is fresh.
        mockMvc.perform(login(ips[4], "strangeunknown03@gmail.com", "Fakepassword@26"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("ERR_RATE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("Account+source bucket bounds repeated attempts for one account from one place")
    void accountIpBucketBindsAccountToSource() throws Exception {
        String ip = "198.51.100.21";
        String email = "acctip-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(login(ip, email, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(ip, email, "wrong")).andExpect(status().isUnauthorized());
        // Per-account-per-IP budget (2) is exhausted before the per-IP budget (3).
        mockMvc.perform(login(ip, email, "wrong"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("A fresh account from the same IP is still admitted after another account exhausted the source")
    void freshAccountNotBuriedByOtherAccount() throws Exception {
        String ip = "198.51.100.30";
        String flooded = "victim-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(login(ip, flooded, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(ip, flooded, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(ip, flooded, "wrong")).andExpect(status().isTooManyRequests());

        mockMvc.perform(login(ip, "fresh-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("OIDC surface is rate limited per source before any SSO work")
    void oidcIpBurstIsDenied() throws Exception {
        String ip = "198.51.100.40";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(fromIp(get("/v1/auth/oidc/start"), ip));
        }
        mockMvc.perform(fromIp(get("/v1/auth/oidc/start"), ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("ERR_RATE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("Repeated attempts with one API-key identifier are denied before BCrypt/DB work")
    void agentKeyIdentifierBurstIsDeniedBeforeAuthentication() throws Exception {
        String ip = "198.51.100.50";
        String identA = "ident-a-" + UUID.randomUUID().toString().substring(0, 8);
        String identB = "ident-b-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(fromIp(post("/v1/actions/evaluate")
                        .header("X-Netherite-Agent-Key", identA + ".secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"), ip))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(fromIp(post("/v1/actions/evaluate")
                        .header("X-Netherite-Agent-Key", identA + ".secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"), ip))
                .andExpect(status().isUnauthorized());

        // Third attempt with the same identifier on a fresh request -> 429 BEFORE the 401 that
        // a bogus key would otherwise always produce.
        mockMvc.perform(fromIp(post("/v1/actions/evaluate")
                        .header("X-Netherite-Agent-Key", identA + ".secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"), ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("ERR_RATE_LIMIT_EXCEEDED"));

        // A different identifier has its own bucket and reaches authentication again.
        mockMvc.perform(fromIp(post("/v1/actions/evaluate")
                        .header("X-Netherite-Agent-Key", identB + ".secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"), ip))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Window expiry releases traffic again (fixed window anchored at first hit)")
    void windowExpiryReleasesTraffic() throws Exception {
        String ip = "198.51.100.60";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(fromIp(get("/v1/auth/me"), ip)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(fromIp(get("/v1/auth/me"), ip))
                .andExpect(status().isTooManyRequests());
        Thread.sleep(1100);
        mockMvc.perform(fromIp(get("/v1/auth/me"), ip)).andExpect(status().isUnauthorized());
    }
}