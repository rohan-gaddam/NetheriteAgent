package io.netherite.ingestion.spi;

import io.netherite.ingestion.model.RegisteredActionTarget;

import java.util.Optional;

/**
 * Deterministic, server-side membership check used by semantic (LLM) action promotion.
 *
 * <p>Implementations MUST consult only trusted server-side state (the registered
 * resource catalog and the agent's explicit action bindings). They MUST NOT accept
 * identity, authorization, or execution instructions from the incoming request or
 * from model output.</p>
 *
 * <p><b>Fail-closed contract:</b> return {@link Optional#empty()} when the resource is
 * not registered/active, when the agent has no active binding, when the action is not
 * explicitly permitted, or when the match is ambiguous. Infrastructure failures should
 * propagate as unchecked exceptions; callers treat any thrown error as a failure to
 * verify and reject the candidate.</p>
 */
public interface RegisteredActionCatalog {

    /**
     * Resolves a candidate (resource, action) pair to a canonical registered target.
     *
     * @param organizationId trusted organization identity from the server-side context
     * @param agentId        trusted agent identity from the server-side context
     * @param resourceName   candidate target resource name (untrusted, already normalized)
     * @param actionType     candidate action type (untrusted, already normalized)
     * @return the canonical registered target, or {@link Optional#empty()} if not verifiable
     */
    Optional<RegisteredActionTarget> resolve(String organizationId,
                                             String agentId,
                                             String resourceName,
                                             String actionType);
}
