package io.netherite.ingestion.schema;

import io.netherite.ingestion.model.ActionParameterSchema;
import io.netherite.ingestion.model.ActionParameterSchema.ParameterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionParameterSchemaParserTest {

    private final ActionParameterSchemaParser parser = new ActionParameterSchemaParser();

    private static Map<String, Object> node(String type, Map<String, Object> extra) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.putAll(extra);
        return node;
    }

    private static Map<String, Object> definition(Map<String, Object> properties) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "OBJECT");
        root.put("additionalProperties", false);
        root.put("properties", properties);
        return root;
    }

    @Test
    @DisplayName("Parses a closed object with a required number and optional string")
    void parsesFlatObject() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("amount", node("NUMBER", Map.of("required", true)));
        props.put("currency", node("STRING", Map.of()));

        ActionParameterSchema schema = parser.parse(definition(props));

        assertEquals(ParameterType.OBJECT, schema.type());
        assertFalse(schema.additionalProperties());
        assertEquals(2, schema.properties().size());
        assertTrue(schema.properties().get("amount").required());
        assertEquals(ParameterType.NUMBER, schema.properties().get("amount").type());
        assertFalse(schema.properties().get("currency").required());
    }

    @Test
    @DisplayName("Parses nested objects and arrays with element schemas")
    void parsesNestedStructures() {
        Map<String, Object> payment = definition(new LinkedHashMap<>(Map.of(
                "amount", node("NUMBER", Map.of("required", true)))));
        Map<String, Object> root = definition(new LinkedHashMap<>(Map.of(
                "payment", payment,
                "tags", node("ARRAY", Map.of("items", node("STRING", Map.of()))))));

        ActionParameterSchema schema = parser.parse(root);

        assertEquals(ParameterType.OBJECT, schema.properties().get("payment").type());
        assertEquals(ParameterType.ARRAY, schema.properties().get("tags").type());
        assertEquals(ParameterType.STRING, schema.properties().get("tags").items().type());
    }

    @Test
    void rejectsNullDefinition() {
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(null));
    }

    @Test
    void rejectsNonObjectRoot() {
        assertThrows(ActionParameterSchemaException.class,
                () -> parser.parse(node("STRING", Map.of())));
    }

    @Test
    void rejectsMissingType() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("properties", new LinkedHashMap<>());
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    void rejectsUnknownKeyword() {
        Map<String, Object> root = definition(new LinkedHashMap<>());
        root.put("$ref", "#/definitions/x");
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    @DisplayName("Rejects unknown keywords nested inside a property node")
    void rejectsUnknownNestedKeyword() {
        Map<String, Object> amount = node("NUMBER", Map.of("pattern", "^[0-9]+$"));
        assertThrows(ActionParameterSchemaException.class,
                () -> parser.parse(definition(new LinkedHashMap<>(Map.of("amount", amount)))));
    }

    @Test
    void rejectsArrayWithoutItems() {
        Map<String, Object> root = definition(new LinkedHashMap<>(Map.of(
                "tags", node("ARRAY", Map.of()))));
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    void rejectsScalarWithProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("child", node("STRING", Map.of()));
        Map<String, Object> weird = node("STRING", Map.of("properties", properties));
        assertThrows(ActionParameterSchemaException.class,
                () -> parser.parse(definition(new LinkedHashMap<>(Map.of("x", weird)))));
    }

    @Test
    void rejectsObjectWithItems() {
        Map<String, Object> obj = node("OBJECT", Map.of("items", node("STRING", Map.of())));
        assertThrows(ActionParameterSchemaException.class,
                () -> parser.parse(definition(new LinkedHashMap<>(Map.of("x", obj)))));
    }

    @Test
    void rejectsReservedPropertyName() {
        Map<String, Object> root = definition(new LinkedHashMap<>(Map.of(
                "organizationId", node("STRING", Map.of()))));
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    void rejectsReservedPropertyNameNested() {
        Map<String, Object> nested = definition(new LinkedHashMap<>(Map.of(
                "approved", node("BOOLEAN", Map.of()))));
        Map<String, Object> root = definition(new LinkedHashMap<>(Map.of("meta", nested)));
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    void rejectsMalformedPropertyName() {
        Map<String, Object> root = definition(new LinkedHashMap<>(Map.of(
                "bad key", node("STRING", Map.of()))));
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    void rejectsUnknownTypeValue() {
        Map<String, Object> root = definition(new LinkedHashMap<>(Map.of(
                "amount", node("FLOAT", Map.of()))));
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    void rejectsAdditionalPropertiesOfWrongType() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "OBJECT");
        root.put("additionalProperties", "no");
        root.put("properties", new LinkedHashMap<>());
        assertThrows(ActionParameterSchemaException.class, () -> parser.parse(root));
    }

    @Test
    @DisplayName("Accepts an explicitly open object")
    void acceptsOpenObject() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "OBJECT");
        root.put("additionalProperties", true);
        root.put("properties", new LinkedHashMap<>(Map.of("amount", node("NUMBER", Map.of()))));

        ActionParameterSchema schema = parser.parse(root);
        assertNotNull(schema);
        assertTrue(schema.additionalProperties());
    }
}
