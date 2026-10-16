package io.netherite.ingestion.schema;

/**
 * Thrown when an operator-submitted or stored parameter-schema definition is not a
 * valid, supported schema. Carries only structural detail (paths and property names),
 * never parameter values.
 */
public class ActionParameterSchemaException extends RuntimeException {

    public ActionParameterSchemaException(String message) {
        super(message);
    }
}
