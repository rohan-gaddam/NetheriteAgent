package io.netherite.llm.provider;

import io.netherite.llm.model.SemanticExtractionCandidate;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCandidateDecoderTest {

    private final LlmCandidateDecoder decoder = new LlmCandidateDecoder();

    @Test
    void decodesValidCandidatePayload() {
        String payload = "{\"actionType\":\"PAYMENT:REFUND\",\"targetResource\":\"PAYMENT_API\","
                + "\"parameters\":{\"amount\":100}}";

        Optional<SemanticExtractionCandidate> result = decoder.decode(payload, "gemini-2.0-flash");

        assertThat(result).isPresent();
        assertThat(result.get().getCandidateActionType()).isEqualTo("PAYMENT:REFUND");
        assertThat(result.get().getCandidateTargetResource()).isEqualTo("PAYMENT_API");
        assertThat(result.get().getCandidateParameters()).containsEntry("amount", 100);
        assertThat(result.get().getModelId()).isEqualTo("gemini-2.0-flash");
        assertThat(result.get().getRawModelOutput()).isEqualTo(payload);
    }

    @Test
    void missingParametersBecomesEmptyMap() {
        Optional<SemanticExtractionCandidate> result =
                decoder.decode("{\"actionType\":\"A\",\"targetResource\":\"R\"}", "m");

        assertThat(result).isPresent();
        assertThat(result.get().getCandidateParameters()).isEmpty();
    }

    @Test
    void rejectsUnknownFields() {
        String payload = "{\"actionType\":\"A\",\"targetResource\":\"R\",\"organizationId\":\"forged\"}";
        assertThat(decoder.decode(payload, "m")).isEmpty();
    }

    @Test
    void rejectsTrailingTokens() {
        String payload = "{\"actionType\":\"A\",\"targetResource\":\"R\"} extra";
        assertThat(decoder.decode(payload, "m")).isEmpty();
    }

    @Test
    void rejectsMissingActionType() {
        assertThat(decoder.decode("{\"targetResource\":\"R\"}", "m")).isEmpty();
    }

    @Test
    void rejectsBlankTargetResource() {
        assertThat(decoder.decode("{\"actionType\":\"A\",\"targetResource\":\"  \"}", "m")).isEmpty();
    }

    @Test
    void rejectsWrongParameterShape() {
        assertThat(decoder.decode("{\"actionType\":\"A\",\"targetResource\":\"R\",\"parameters\":\"nope\"}", "m"))
                .isEmpty();
    }

    @Test
    void rejectsNonJsonAndEmptyInput() {
        assertThat(decoder.decode("not json at all", "m")).isEmpty();
        assertThat(decoder.decode("", "m")).isEmpty();
        assertThat(decoder.decode(null, "m")).isEmpty();
        assertThat(decoder.decode("{}", "m")).isEmpty();
    }

    @Test
    void acceptsJsonWrappedInCodeFence() {
        String payload = "```json\n{\"actionType\":\"A\",\"targetResource\":\"R\",\"parameters\":{}}\n```";
        Optional<SemanticExtractionCandidate> result = decoder.decode(payload, "m");

        assertThat(result).isPresent();
        assertThat(result.get().getCandidateActionType()).isEqualTo("A");
        assertThat(result.get().getCandidateParameters()).isEqualTo(Map.of());
    }
}
