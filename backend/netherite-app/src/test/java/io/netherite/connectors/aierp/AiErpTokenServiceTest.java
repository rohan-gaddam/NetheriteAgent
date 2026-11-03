package io.netherite.connectors.aierp;

import io.netherite.common.exception.NetheriteException;
import io.netherite.connectors.spi.SsrfGuardValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiErpTokenServiceTest {

    private SsrfGuardValidator ssrfGuardValidator;
    private RestTemplate restTemplate;
    private AiErpTokenService tokenService;

    @BeforeEach
    void setUp() {
        ssrfGuardValidator = Mockito.mock(SsrfGuardValidator.class);
        when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(true);

        tokenService = new AiErpTokenService(ssrfGuardValidator);
        restTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(tokenService, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("Acquire new token calls login endpoint and returns token")
    void testAcquireTokenSuccess() {
        String baseUrl = "https://erp.example.com";
        String loginResponse = "{\"success\":true,\"object\":\"test-jwt-token-12345\"}";

        when(restTemplate.exchange(
                eq("https://erp.example.com/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>(loginResponse, HttpStatus.OK));

        Map<String, String> creds = Map.of("email", "admin@erp.local", "password", "secret123");
        String token = tokenService.getValidToken(baseUrl, creds, "org-1", "res-1");

        assertThat(token).isEqualTo("test-jwt-token-12345");

        // Subsequent call uses cache (no additional HTTP call)
        String cachedToken = tokenService.getValidToken(baseUrl, creds, "org-1", "res-1");
        assertThat(cachedToken).isEqualTo("test-jwt-token-12345");
        verify(restTemplate, times(1)).exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("Login failure with success=false throws exception")
    void testLoginApplicationFailure() {
        String baseUrl = "https://erp.example.com";
        String errorResponse = "{\"success\":false,\"message\":\"Invalid credentials\"}";

        when(restTemplate.exchange(
                any(String.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>(errorResponse, HttpStatus.OK));

        Map<String, String> creds = Map.of("email", "bad@erp.local", "password", "wrong");

        assertThatThrownBy(() -> tokenService.getValidToken(baseUrl, creds, "org-1", "res-1"))
                .isInstanceOf(NetheriteException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    @DisplayName("Token refresh calls refresh endpoint")
    void testRefreshToken() {
        String baseUrl = "https://erp.example.com";
        String loginResponse = "{\"success\":true,\"object\":\"initial-token\"}";
        String refreshResponse = "{\"success\":true,\"object\":\"refreshed-token-999\"}";

        when(restTemplate.exchange(
                eq("https://erp.example.com/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>(loginResponse, HttpStatus.OK));

        when(restTemplate.exchange(
                eq("https://erp.example.com/api/v3/en_US/auth/jwt/token/refresh"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>(refreshResponse, HttpStatus.OK));

        Map<String, String> creds = Map.of("email", "admin@erp.local", "password", "secret");
        String token = tokenService.getValidToken(baseUrl, creds, "org-1", "res-1");
        assertThat(token).isEqualTo("initial-token");

        String refreshed = tokenService.refreshTokenOrReacquire(baseUrl, creds, "org-1", "res-1");
        assertThat(refreshed).isEqualTo("refreshed-token-999");
    }

    @Test
    @DisplayName("Tenant isolation: tokens are cached per orgId and resourceId")
    void testTenantIsolation() {
        String baseUrl = "https://erp.example.com";

        when(restTemplate.exchange(
                eq("https://erp.example.com/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"object\":\"token-org-1\"}", HttpStatus.OK))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"object\":\"token-org-2\"}", HttpStatus.OK));

        Map<String, String> creds = Map.of("email", "admin@erp.local", "password", "secret");

        String t1 = tokenService.getValidToken(baseUrl, creds, "org-1", "res-1");
        String t2 = tokenService.getValidToken(baseUrl, creds, "org-2", "res-1");

        assertThat(t1).isEqualTo("token-org-1");
        assertThat(t2).isEqualTo("token-org-2");
        verify(restTemplate, times(2)).exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("SSRF blocked destination throws NetheriteException")
    void testSsrfBlocked() {
        when(ssrfGuardValidator.isUriPermitted(any(URI.class))).thenReturn(false);
        String baseUrl = "http://127.0.0.1:8080";
        Map<String, String> creds = Map.of("email", "admin@erp.local", "password", "secret");

        assertThatThrownBy(() -> tokenService.getValidToken(baseUrl, creds, "org-1", "res-1"))
                .isInstanceOf(NetheriteException.class)
                .hasMessageContaining("network validation");
    }
}
