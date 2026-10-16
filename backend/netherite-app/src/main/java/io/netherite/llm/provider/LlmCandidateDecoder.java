package io.netherite.llm.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.llm.model.SemanticExtractionCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decodes the untrusted textual payload returned by an LLM into a
 * {@link SemanticExtractionCandidate}.
 *
 * <p>Fail-closed: the payload must be a single JSON object with exactly the expected
 * fields ({@code actionType}, {@code targetResource}, {@code parameters}). Any unknown
 * field, trailing content, missing/blank identity field, or wrong value shape yields
 * {@link Optional#empty()} so the caller never treats partial model output as an action.
 * The raw text is retained on the candidate only as an opaque untrusted value and is
 * never logged.</p>
 */
@Slf4j
public class LlmCandidateDecoder {

    private final ObjectMapper objectMapper;

    public LlmCandidateDecoder() {
        this(defaultMapper());
    }

    public LlmCandidateDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        return mapper;
    }

    public Optional<SemanticExtractionCandidate> decode(String modelText, String modelId) {
        if (modelText == null || modelText.isBlank()) {
            return Optional.empty();
        }
        String json = stripCodeFence(modelText.trim());
        if (json.isEmpty()) {
            return Optional.empty();
        }

        CandidatePayload payload;
        try {
            payload = objectMapper.readValue(json, CandidatePayload.class);
        } catch (Exception ex) {
            log.debug("LLM candidate payload could not be decoded; failing closed");
            return Optional.empty();
        }

        if (payload == null
                || payload.getActionType() == null || payload.getActionType().isBlank()
                || payload.getTargetResource() == null || payload.getTargetResource().isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> parameters = payload.getParameters() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(payload.getParameters());

        return Optional.of(SemanticExtractionCandidate.builder()
                .candidateActionType(payload.getActionType())
                .candidateTargetResource(payload.getTargetResource())
                .candidateParameters(parameters)
                .rawModelOutput(modelText)
                .modelId(modelId)
                .build());
    }

    /**
     * Tolerates a model that wrapped the JSON in a fenced code block despite the
     * requested {@code application/json} response type. The content is still parsed
     * strictly afterwards.
     */
    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        String body = text.substring(firstNewline + 1);
        int fenceEnd = body.lastIndexOf("```");
        if (fenceEnd >= 0) {
            body = body.substring(0, fenceEnd);
        }
        return body.trim();
    }

    @SuppressWarnings("unused")
    public static class CandidatePayload {
        private String actionType;
        private String targetResource;
        private Map<String, Object> parameters;

        public String getActionType() {
            return actionType;
        }

        public void setActionType(String actionType) {
            this.actionType = actionType;
        }

        public String getTargetResource() {
            return targetResource;
        }

        public void setTargetResource(String targetResource) {
            this.targetResource = targetResource;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}
