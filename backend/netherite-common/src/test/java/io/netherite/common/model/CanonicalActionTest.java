package io.netherite.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalActionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Should serialize and deserialize CanonicalAction correctly")
    void testCanonicalActionSerialization() throws Exception {
        CanonicalAction action = CanonicalAction.builder()
                .actionId("act_test_001")
                .organizationId("org_test")
                .agentId("agt_test")
                .userId("usr_test")
                .actionType("PAYMENT:REFUND")
                .targetResource("PAYMENT_API")
                .parameters(Map.of("amount", 5000, "currency", "INR"))
                .timestamp(Instant.now())
                .sourceMetadata(SourceMetadata.builder().clientIp("127.0.0.1").build())
                .build();

        String json = objectMapper.writeValueAsString(action);
        assertThat(json).contains("act_test_001", "PAYMENT:REFUND", "5000");

        CanonicalAction deserialized = objectMapper.readValue(json, CanonicalAction.class);
        assertThat(deserialized.getActionId()).isEqualTo("act_test_001");
        assertThat(deserialized.getActionType()).isEqualTo("PAYMENT:REFUND");
        assertThat(deserialized.getParameters()).containsEntry("currency", "INR");
    }
}
