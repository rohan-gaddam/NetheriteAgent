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

class OpenRouterLlmProviderTest {

    private static final String MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate;
    private SsrfGuardValidator ssrfGuard;
    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        ssrfGuard = mock(SsrfGuardValidator.class);
        properties = new LlmProperties();
        properties.setProvider(LlmProperties.PROVIDER_OPENROUTER);
        properties.setTimeoutMs(5000);
        properties.setMaxOutputTokens(256);
        properties.setMaxResponseBytes(16_384);
        properties.getOpenrouter().setApiKey("test-api-key");
        properties.getOpenrouter().setBaseUrl("https://openrouter.ai/api/v1");
        properties.getOpenrouter().setDefaultModel(MODEL);
        when(ssrfGuard.isUriPermittedForOperatorConfigured(any(URI.class))).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private OpenRouterLlmProvider provider() {
        return new OpenRouterLlmProvider(properties, ssrfGuard, restTemplate);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(ResponseEntity<byte[]> response) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sendsOpenAiChatCompletionsRequestAndMapsCandidate() throws Exception {
        String modelJson = "{\"actionType\":\"PAYMENT:REFUND\",\"targetResource\":\"PAYMENT_API\","
                + "\"parameters\":{\"amount\":10}}";
        Map<String, Object> openRouterResponse = Map.of(
                "id", "gen-test-1",
                "model", MODEL,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", modelJson),
                        "finish_reason", "stop")));
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(openRouterResponse)));

        Optional<SemanticExtractionCandidate> result =
                provider().extractCandidateAction("Refund 10 to the customer", "org-1");

        assertThat(result).isPresent();
        assertThat(result.get().getCandidateActionType()).isEqualTo("PAYMENT:REFUND");
        assertThat(result.get().getCandidateTargetResource()).isEqualTo("PAYMENT_API");
        assertThat(result.get().getModelId()).isEqualTo(MODEL);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(byte[].class));

        assertThat(uriCaptor.getValue().toString()).isEqualTo(
                "https://openrouter.ai/api/v1/chat/completions");

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer test-api-key");
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertThat(body).containsEntry("model", MODEL);
        assertThat(body).containsEntry("temperature", 0.0);
        assertThat(body).containsEntry("max_tokens", 256);
        assertThat(body).containsKey("response_format");

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat(messages.get(1).get("role")).isEqualTo("user");
        assertThat(messages.get(1).get("content")).isEqualTo("Refund 10 to the customer");
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
        Map<String, Object> openRouterResponse = Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("role", "assistant", "content", "this is not json"))));
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(openRouterResponse)));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedOnErrorPayloadWithoutChoices() throws Exception {
        Map<String, Object> errorPayload = Map.of(
                "error", Map.of("code", 401, "message", "No auth credentials found"));
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(errorPayload)));

        assertThat(provider().extractCandidateAction("do something", "org-1")).isEmpty();
    }

    @Test
    void failsClosedWhenNoChoicesReturned() throws Exception {
        stubResponse(ResponseEntity.ok(objectMapper.writeValueAsBytes(Map.of("choices", List.of()))));

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
        blankKey.getOpenrouter().setApiKey("   ");

        assertThatThrownBy(() -> new OpenRouterLlmProvider(blankKey, ssrfGuard, restTemplate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
    }

    @Test
    void reportsAvailableWhenKeyPresent() {
        assertThat(provider().isAvailable()).isTrue();
    }
}
