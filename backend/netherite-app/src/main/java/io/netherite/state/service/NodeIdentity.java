package io.netherite.state.service;

import io.netherite.state.config.NetheriteDistributedProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stable per-process identifier used to attribute distributed execution claims and
 * to correlate scheduler ownership in logs across replicas.
 */
@Component
public class NodeIdentity {

    private final String id;

    public NodeIdentity(NetheriteDistributedProperties properties) {
        String configured = properties.getNode().getId();
        this.id = (configured == null || configured.isBlank()) ? "node-" + UUID.randomUUID() : configured;
    }

    public String getId() {
        return id;
    }
}