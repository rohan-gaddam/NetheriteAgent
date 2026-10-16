package io.netherite.ingestion.schema;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.ingestion.model.ActionParameterSchema;
import io.netherite.ingestion.model.ActionParameterSchema.ParameterType;
import io.netherite.ingestion.promotion.ActionParameterRules;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses and validates an untrusted parameter-schema definition into the deterministic
 * {@link ActionParameterSchema} model.
 *
 * <p>The accepted wire format is a deliberately strict subset of JSON Schema. Unknown
 * keywords are rejected (so an operator cannot smuggle in a feature the promoter does
 * not enforce), the root must be an {@code OBJECT}, every node must declare a
 * {@code type}, arrays must declare {@code items}, scalars must not declare
 * {@code properties}/{@code items}, property names must be well-formed and must not
 * collide with reserved identity/authorization names, and depth/property counts are
 * bounded.</p>
 *
 * <p>Every failure throws {@link ActionParameterSchemaException}. Callers on the write
 * path surface it as an invalid-request error; callers on the read path treat it as a
 * failure to verify and fail closed.</p>
 */
@Component
public class ActionParameterSchemaParser {

    private final ObjectMapper objectMapper;

    public ActionParameterSchemaParser() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    }

    /**
     * Converts a raw definition map into a validated {@link ActionParameterSchema}.
     *
     * @throws ActionParameterSchemaException when the definition is absent, malformed,
     *         uses unsupported keywords, or violates the schema grammar
     */
    public ActionParameterSchema parse(Map<String, Object> definition) {
        if (definition == null) {
            throw new ActionParameterSchemaException("Schema definition is required");
        }

        ActionParameterSchema schema;
        try {
            schema = objectMapper.convertValue(definition, ActionParameterSchema.class);
        } catch (IllegalArgumentException e) {
            throw new ActionParameterSchemaException(
                    "Schema definition contains an unsupported or malformed field");
        }

        List<String> problems = new ArrayList<>();
        if (schema == null) {
            problems.add("schema definition must be an object");
        } else if (schema.type() != ParameterType.OBJECT) {
            problems.add("schema root must have type OBJECT");
        } else {
            validateNode(schema, "schema", 0, problems);
        }

        if (!problems.isEmpty()) {
            throw new ActionParameterSchemaException(String.join("; ", problems));
        }
        return schema;
    }

    private void validateNode(ActionParameterSchema node, String path, int depth, List<String> problems) {
        if (node == null) {
            problems.add(path + " must not be null");
            return;
        }
        if (depth > ActionParameterRules.MAX_SCHEMA_DEPTH) {
            problems.add(path + " exceeds maximum schema depth");
            return;
        }
        ParameterType type = node.type();
        if (type == null) {
            problems.add(path + " is missing a type");
            return;
        }
        switch (type) {
            case OBJECT -> validateObject(node, path, depth, problems);
            case ARRAY -> validateArray(node, path, depth, problems);
            default -> validateScalar(node, path, problems);
        }
    }

    private void validateObject(ActionParameterSchema node, String path, int depth, List<String> problems) {
        if (node.items() != null) {
            problems.add(path + " must not declare items for an object");
        }
        if (node.properties().size() > ActionParameterRules.MAX_SCHEMA_PROPERTIES) {
            problems.add(path + " declares too many properties");
        }
        for (Map.Entry<String, ActionParameterSchema> entry : node.properties().entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > ActionParameterRules.MAX_KEY_LENGTH
                    || !ActionParameterRules.isWellFormedParameterKey(key)) {
                problems.add(path + " declares an invalid property name");
            } else if (ActionParameterRules.isReservedParameterKey(key)) {
                problems.add(path + " property '" + key
                        + "' uses a reserved identity/authorization name");
            }
            validateNode(entry.getValue(), path + "." + key, depth + 1, problems);
        }
    }

    private void validateArray(ActionParameterSchema node, String path, int depth, List<String> problems) {
        if (!node.properties().isEmpty()) {
            problems.add(path + " must not declare properties for an array");
        }
        if (node.items() == null) {
            problems.add(path + " must declare items for an array");
        } else {
            validateNode(node.items(), path + "[]", depth + 1, problems);
        }
    }

    private void validateScalar(ActionParameterSchema node, String path, List<String> problems) {
        if (node.items() != null) {
            problems.add(path + " must not declare items for a scalar");
        }
        if (!node.properties().isEmpty()) {
            problems.add(path + " must not declare properties for a scalar");
        }
    }
}
