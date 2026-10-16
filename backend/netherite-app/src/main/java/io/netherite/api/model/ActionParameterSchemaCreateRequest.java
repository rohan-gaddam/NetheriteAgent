package io.netherite.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request to create a new DRAFT parameter schema for a resource/action pair.
 *
 * <p>{@code schema} is the strict JSON-Schema-subset definition. It is intentionally
 * accepted as a raw map so the server-side {@code ActionParameterSchemaParser} — not
 * the web layer — is the single place that decides what a valid schema is.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionParameterSchemaCreateRequest {

    @NotBlank(message = "Action type is required")
    private String actionType;

    @NotNull(message = "Schema definition is required")
    private Map<String, Object> schema;
}
