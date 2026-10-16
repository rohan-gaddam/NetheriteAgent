package io.netherite.routing.spi;

import io.netherite.common.model.CanonicalAction;
import io.netherite.routing.model.ResourceRoute;

import java.util.Optional;

/**
 * Deterministic service for resolving enterprise resource routes from canonical actions.
 */
public interface ResourceRoutingService {

    /**
     * Resolves the bound enterprise resource route for the given canonical action.
     */
    Optional<ResourceRoute> resolveRoute(CanonicalAction action);

    /**
     * Validates whether an agent has an explicit, active binding to execute the specified action on the target resource.
     */
    boolean isBindingAuthorized(String organizationId, String agentId, String resourceId, String actionType);
}
