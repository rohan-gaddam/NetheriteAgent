package io.netherite.connectors.spi;

import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.model.ConnectorExecutionRequest;
import io.netherite.connectors.model.ConnectorExecutionResult;

/**
 * Pluggable SPI for executing authorized actions against enterprise resources.
 */
public interface ResourceConnector {

    /**
     * Supported transport connector type.
     */
    ConnectorType getConnectorType();

    /**
     * Executes the authorized action against the external enterprise system.
     */
    ConnectorExecutionResult execute(ConnectorExecutionRequest request);
}
