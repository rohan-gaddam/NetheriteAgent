package io.netherite.admin.dto;

import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Map;

/**
 * Request for registering an enterprise resource.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceRegistrationRequest {

    @NotBlank(message = "Resource name is required")
    private String name;

    @NotNull(message = "Connector type is required")
    private ConnectorType connectorType;

    @NotBlank(message = "Base URL is required")
    private String baseUrl;

    private String description;
    private SensitivityLevel sensitivityLevel;

    @ToString.Exclude
    private Map<String, String> credentials;
}
