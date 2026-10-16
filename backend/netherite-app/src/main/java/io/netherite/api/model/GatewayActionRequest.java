package io.netherite.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Production gateway request contract for external AI agents.
 * Agent identity and organization come from the authenticated API key, NOT from this request body.
 * The resource field identifies a registered resource in the customer's catalog - not a URL.
 *
 * <p>Either a fully structured action ({@code action} + {@code resource}) or a natural-language
 * {@code rawPrompt} must be supplied. A {@code rawPrompt} is interpreted by the configured LLM
 * provider into a candidate action that is then validated by the deterministic promoter; it
 * never carries identity or authorization.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayActionRequest {

    private String action;

    private String resource;

    private Map<String, Object> parameters;

    /** Natural-language request interpreted semantically when action/resource are absent. */
    private String rawPrompt;

    private String idempotencyKey;
}
