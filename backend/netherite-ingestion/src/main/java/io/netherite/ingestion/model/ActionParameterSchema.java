package io.netherite.ingestion.model;

import java.util.Map;

/**
 * Deterministic, recursive parameter schema declared for a registered
 * (resource, action) pair.
 *
 * <p>Used by the semantic action promoter to enforce parameter structure without
 * trusting any part of the model output, and persisted by the control plane as the
 * operator-authored parameter contract. A schema is purely declarative and intentionally
 * a strict subset of JSON Schema — only {@code type}, {@code required},
 * {@code properties}, {@code additionalProperties} and {@code items} are expressible:</p>
 *
 * <ul>
 *   <li>{@code type} - the expected {@link ParameterType} for the node</li>
 *   <li>{@code required} - whether this property must be present in its parent object</li>
 *   <li>{@code properties} - the declared children of an {@code OBJECT} node</li>
 *   <li>{@code additionalProperties} - when {@code false} (the default) the object is
 *       <b>closed</b>: any key not present in {@code properties} is rejected</li>
 *   <li>{@code items} - the element schema of an {@code ARRAY} node</li>
 * </ul>
 *
 * <p>Anything outside this subset is rejected when a schema definition is stored, so a
 * schema can never quietly become permissive.</p>
 */
public record ActionParameterSchema(
        ParameterType type,
        boolean required,
        Map<String, ActionParameterSchema> properties,
        boolean additionalProperties,
        ActionParameterSchema items) {

    public ActionParameterSchema {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }

    public enum ParameterType {
        STRING,
        NUMBER,
        BOOLEAN,
        OBJECT,
        ARRAY
    }

    public static ActionParameterSchema object(Map<String, ActionParameterSchema> properties,
                                               boolean additionalProperties) {
        return new ActionParameterSchema(ParameterType.OBJECT, false, properties, additionalProperties, null);
    }

    public static ActionParameterSchema array(ActionParameterSchema items) {
        return new ActionParameterSchema(ParameterType.ARRAY, false, Map.of(), false, items);
    }

    public static ActionParameterSchema string() {
        return scalar(ParameterType.STRING);
    }

    public static ActionParameterSchema number() {
        return scalar(ParameterType.NUMBER);
    }

    public static ActionParameterSchema booleanType() {
        return scalar(ParameterType.BOOLEAN);
    }

    private static ActionParameterSchema scalar(ParameterType type) {
        return new ActionParameterSchema(type, false, Map.of(), false, null);
    }

    /** Returns a copy of this node marked as a required property of its parent object. */
    public ActionParameterSchema asRequired() {
        return new ActionParameterSchema(type, true, properties, additionalProperties, items);
    }
}
