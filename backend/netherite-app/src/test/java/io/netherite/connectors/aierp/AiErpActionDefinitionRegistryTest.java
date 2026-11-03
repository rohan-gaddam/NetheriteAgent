package io.netherite.connectors.aierp;

import io.netherite.common.model.SensitivityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiErpActionDefinitionRegistryTest {

    private AiErpActionDefinitionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AiErpActionDefinitionRegistry();
    }

    @Test
    @DisplayName("Canonical action AI_ERP:GET_SCHEMA is resolved correctly")
    void testCanonicalSchemaAction() {
        Optional<AiErpActionDefinition> def = registry.findDefinition("AI_ERP:GET_SCHEMA");
        assertThat(def).isPresent();
        assertThat(def.get().getHttpMethod()).isEqualTo("GET");
        assertThat(def.get().getPathTemplate()).isEqualTo("/api/v3.0/p/agent/schema");
        assertThat(def.get().isReadOnly()).isTrue();
        assertThat(def.get().getSensitivityLevel()).isEqualTo(SensitivityLevel.LOW);
    }

    @Test
    @DisplayName("Aliases like GET_SCHEMA and SCHEMA resolve to canonical AI_ERP:GET_SCHEMA")
    void testAliases() {
        Optional<AiErpActionDefinition> def1 = registry.findDefinition("GET_SCHEMA");
        Optional<AiErpActionDefinition> def2 = registry.findDefinition("SCHEMA");
        Optional<AiErpActionDefinition> def3 = registry.findDefinition("ai_erp:get_schema");

        assertThat(def1).isPresent();
        assertThat(def2).isPresent();
        assertThat(def3).isPresent();
        assertThat(def1.get().getActionId()).isEqualTo("AI_ERP:GET_SCHEMA");
        assertThat(def2.get().getActionId()).isEqualTo("AI_ERP:GET_SCHEMA");
        assertThat(def3.get().getActionId()).isEqualTo("AI_ERP:GET_SCHEMA");
    }

    @Test
    @DisplayName("Mutating actions have appropriate sensitivity and readOnly=false")
    void testMutatingActions() {
        Optional<AiErpActionDefinition> po = registry.findDefinition("AI_ERP:PLACE_PURCHASE_ORDER");
        assertThat(po).isPresent();
        assertThat(po.get().getHttpMethod()).isEqualTo("POST");
        assertThat(po.get().isReadOnly()).isFalse();
        assertThat(po.get().getSensitivityLevel()).isEqualTo(SensitivityLevel.HIGH);

        Optional<AiErpActionDefinition> pay = registry.findDefinition("AI_ERP:RECORD_PAYMENT");
        assertThat(pay).isPresent();
        assertThat(pay.get().getSensitivityLevel()).isEqualTo(SensitivityLevel.CRITICAL);

        Optional<AiErpActionDefinition> query = registry.findDefinition("AI_ERP:QUERY");
        assertThat(query).isPresent();
        assertThat(query.get().getHttpMethod()).isEqualTo("POST");
        assertThat(query.get().isReadOnly()).isTrue();
        assertThat(query.get().getPathTemplate()).isEqualTo("/api/v3.0/p/agent/query");

        Optional<AiErpActionDefinition> createRec = registry.findDefinition("CREATE_RECORD");
        assertThat(createRec).isPresent();
        assertThat(createRec.get().getHttpMethod()).isEqualTo("POST");
        assertThat(createRec.get().getPathTemplate()).isEqualTo("/api/v3.0/p/agent/records/{entity}");

        Optional<AiErpActionDefinition> updateRec = registry.findDefinition("UPDATE_RECORD");
        assertThat(updateRec).isPresent();
        assertThat(updateRec.get().getHttpMethod()).isEqualTo("PATCH");
        assertThat(updateRec.get().getPathTemplate()).isEqualTo("/api/v3.0/p/agent/records/{entity}/{id}");

        Optional<AiErpActionDefinition> deleteRec = registry.findDefinition("DELETE_RECORD");
        assertThat(deleteRec).isPresent();
        assertThat(deleteRec.get().getHttpMethod()).isEqualTo("DELETE");
        assertThat(deleteRec.get().getSensitivityLevel()).isEqualTo(SensitivityLevel.CRITICAL);
    }

    @Test
    @DisplayName("Normalized action name handles hyphens and prefixes")
    void testNormalizeActionName() {
        assertThat(registry.normalizeActionName("ai-erp:get-schema")).isEqualTo("AI_ERP:GET_SCHEMA");
        assertThat(registry.normalizeActionName("AIERP_PLACE_PURCHASE_ORDER")).isEqualTo("AI_ERP:PLACE_PURCHASE_ORDER");
        assertThat(registry.normalizeActionName("ai_erp_setup_status")).isEqualTo("AI_ERP:SETUP_STATUS");
    }

    @Test
    @DisplayName("Unknown action returns empty")
    void testUnknownAction() {
        assertThat(registry.findDefinition("NON_EXISTENT_ACTION")).isEmpty();
        assertThat(registry.findDefinition(null)).isEmpty();
        assertThat(registry.findDefinition("")).isEmpty();
    }
}
