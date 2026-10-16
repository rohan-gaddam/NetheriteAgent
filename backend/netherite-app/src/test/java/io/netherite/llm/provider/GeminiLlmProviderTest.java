package io.netherite.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.llm.config.LlmProperties;
import io.netherite.llm.model.SemanticExtractionCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeminiLlmProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate;
    private SsrfGuardValidator ssrfGuard;
    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        ssrfGuard = mock(SsrfGuardValidator.class);
        properties = new LlmProperties();
        properties.setProvider(LlmProperties.PROVIDER_GEMINI);
        properties.setTimeoutMs(5000);
        properties.setMaxOutputTokens(256);
        properties.setMaxResponseBytes(16_384);
        properties.getGemini().setApiKey("test-api-key");
        properties.getGemini().setBaseUrl("https://generativelanguage.googleapis.com");
        properties.getGemini().setDefaultModel("gemini-2.0-flash");
        when(ssrfGuard.isUriPermittedForOperatorConfigured(any(URI.class))).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private GeminiLlmProvider provider() {
        return new GeminiLlmProvider(properties, ssrfGuard, restTemplate);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(ResponseEntity<byte[]> response) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sendsNativeGeminiRequestAndMapsCandidate() throws Exception {
        String modelJson = "{\"actionType\":\"PAYMENT:REFUND\",\"targetResource\":\"PAYMENT_API\","
                + "\"parameters\":{\"amount\":10}}";
        Map<String, Object> geminiResponse = Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("role", "model", "parts", List.of(Map.of("text", modelJson))),
                        "finishReason", "STOP")),
                "modelVersion", "gemini-2.0-flash-001");
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(geminiResponse)));

        Optional<SemanticExtractionCandidate> result =
                provider().extractCandidateAction("Refund 10 to the customer", "org-1");

        assertThat(result).isPresent();
        assertThat(result.get().getCandidateActionType()).isEqualTo("PAYMENT:REFUND");
        assertThat(result.get().getCandidateTargetResource()).isEqualTo("PAYMENT_API");
        assertThat(result.get().getModelId()).isEqualTo("gemini-2.0-flash-001");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(byte[].class));

        assertThat(uriCaptor.getValue().toString()).isEqualTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent");

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("x-goog-api-key")).isEqualTo("test-api-key");
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertThat(body).containsKeys("systemInstruction", "contents", "generationConfig");

        Map<String, Object> generationConfig = (Map<String, Object>) body.get("generationConfig");
        assertThat(generationConfig).containsEntry("responseMimeType", "application/json");
        assertThat(generationConfig).containsEntry("maxOutputTokens", 256);

        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("contents");
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).get("role")).isEqualTo("user");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) contents.get(0).get("parts");
        assertThat(parts.get(0).get("text")).isEqualTo("Refund 10 to the customer");
    }

    @Test
    void failsClosedAndDoesNotCallProviderWhenSsrfDeniesEndpoint() {
        when(ssrfGuard.isUriPermittedForOperatorConfigured(any(URI.class))).thenReturn(false);

        Optional<SemanticExtractionCandidate> result = provider().extractCandidateAction("do something", "org-1");

        assertThat(result).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void failsClosedOnNon2xxResponse() {
        stubResponse(new ResponseEntity<>(new byte[0], HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedOnOversizedResponse() {
        properties.setMaxResponseBytes(16);
        stubResponse(ResponseEntity.ok(new byte[64]));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedOnTransportError() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedOnMalformedModelOutput() throws Exception {
        String modelJson = "this is not json";
        Map<String, Object> geminiResponse = Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("role", "model", "parts", List.of(Map.of("text", modelJson))))));
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(geminiResponse)));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedWhenNoCandidatesReturned() throws Exception {
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(Map.of("candidates", List.of()))));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedOnBlankPromptWithoutCallingProvider() {
        assertThat(provider().extractCandidateAction("   ", "org-1")).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void rejectsBlankApiKeyAtConstructionWithoutLeakingAnything() {
        LlmProperties blankKey = new LlmProperties();
        blankKey.getGemini().setApiKey("   ");

        assertThatThrownBy(() -> new GeminiLlmProvider(blankKey, ssrfGuard, restTemplate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void reportsAvailableWhenKeyPresent() {
        assertThat(provider().isAvailable()).isTrue();
    }
}
