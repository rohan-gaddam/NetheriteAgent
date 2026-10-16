package io.netherite.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Integration information returned by the gateway integration endpoint.
 * Provides customers with the information needed to configure their AI agents.
 * Does NOT expose secrets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayIntegrationInfo {

    private String gatewayEndpoint;
    private String authenticationMethod;
    private String authenticationHeader;
    private String requestFormat;
    private String documentation;
}
