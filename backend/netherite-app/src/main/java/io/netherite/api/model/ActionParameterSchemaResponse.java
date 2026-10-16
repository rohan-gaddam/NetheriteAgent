package io.netherite.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionParameterSchemaResponse {

    private String id;
    private String organizationId;
    private String resourceId;
    private String actionType;
    private int versionNumber;
    private Map<String, Object> schema;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant activatedAt;
}
