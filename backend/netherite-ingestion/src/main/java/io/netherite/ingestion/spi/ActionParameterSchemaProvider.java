package io.netherite.ingestion.spi;

import io.netherite.ingestion.model.ActionParameterSchema;

import java.util.Optional;

/**
 * Supplies the deterministic parameter schema for a registered (resource, action) pair.
 *
 * <p>The control plane registers a database-backed provider (see
 * {@code DbActionParameterSchemaProvider}) that serves operator-authored, versioned
 * schemas tied to the registered resource/action contract. When no provider yields an
 * <em>active</em> schema, the promoter treats any supplied parameters as
 * <b>unverifiable</b> and rejects them (fail-closed) rather than pretending they were
 * validated.</p>
 *
 * <p><b>Fail-closed contract:</b> a provider MUST return
 * {@link Optional#empty()} when no active schema exists, and MUST throw when a stored
 * schema cannot be trusted (e.g. it is malformed); callers treat a thrown error as a
 * failure to verify and reject the candidate.</p>
 */
public interface ActionParameterSchemaProvider {

    /**
     * Returns the declared schema for the (resource, action) pair, if any.
     *
     * @param organizationId trusted organization identity from the server-side context
     * @param resourceName   canonical registered resource name (server-derived)
     * @param actionType     canonical action type (server-derived, normalized)
     * @return the schema, or {@link Optional#empty()} when no schema is declared
     */
    Optional<ActionParameterSchema> schemaFor(String organizationId,
                                              String resourceName,
                                              String actionType);
}
