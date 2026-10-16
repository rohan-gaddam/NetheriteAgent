package io.netherite.ingestion.promotion;

import io.netherite.common.context.TenantContext;
import io.netherite.common.model.CanonicalAction;
import io.netherite.ingestion.model.ActionParameterSchema;
import io.netherite.ingestion.model.ActionParameterSchema.ParameterType;
import io.netherite.ingestion.model.RegisteredActionTarget;
import io.netherite.ingestion.model.SemanticActionPromotionResult;
import io.netherite.ingestion.spi.ActionParameterSchemaProvider;
import io.netherite.ingestion.spi.RegisteredActionCatalog;
import io.netherite.llm.model.SemanticExtractionCandidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic security validator that promotes an untrusted
 * {@link SemanticExtractionCandidate} (e.g. produced by an LLM) into a
 * {@link CanonicalAction}.
 *
 * <p>Every field of the candidate is treated as untrusted. The promoter never grants
 * authority; it only <b>removes</b> fields that cannot be deterministically verified
 * against server-side truth. It is deliberately independent of connector execution,
 * approval, and policy code — a promoted action must still traverse the full existing
 * OPA / approval / idempotency / execution / audit pipeline.</p>
 *
 * <p>Rules enforced:</p>
 * <ul>
 *   <li>Identity (organization, agent, user, principal type) is taken solely from the
 *       trusted {@link TenantContext}; candidate output can never influence it.</li>
 *   <li>Action type and target resource must be well-formed identifiers, are
 *       upper-cased, and must resolve in the {@link RegisteredActionCatalog} with an
 *       explicit agent binding. An empty binding allow-list is <b>not</b> treated as
 *       "allow all".</li>
 *   <li>Parameters must be structurally sound and must match a declared
 *       {@link ActionParameterSchema}. When no schema exists, non-empty parameters are
 *       rejected as unverifiable (fail-closed).</li>
 *   <li>Identity / authorization control keys (organizationId, agentId, decision,
 *       approved, role, ...) are rejected anywhere in the parameter tree.</li>
 *   <li>Malformed, ambiguous and contradictory candidates are rejected; on any
 *       infrastructure failure the promoter fails closed.</li>
 * </ul>
 *
 * <p>Raw model output and secret values are never logged or echoed.</p>
 */
public class SemanticActionPromoter {

    private static final int MAX_ACTION_LENGTH = ActionParameterRules.MAX_ACTION_LENGTH;
    private static final int MAX_RESOURCE_LENGTH = ActionParameterRules.MAX_RESOURCE_LENGTH;
    private static final int MAX_PARAMETER_COUNT = 256;
    private static final int MAX_KEY_LENGTH = ActionParameterRules.MAX_KEY_LENGTH;
    private static final int MAX_STRING_LENGTH = 8192;
    private static final int MAX_DEPTH = ActionParameterRules.MAX_SCHEMA_DEPTH;

    private final RegisteredActionCatalog catalog;
    private final List<ActionParameterSchemaProvider> schemaProviders;

    public SemanticActionPromoter(RegisteredActionCatalog catalog,
                                  List<ActionParameterSchemaProvider> schemaProviders) {
        this.catalog = catalog;
        this.schemaProviders = schemaProviders != null ? List.copyOf(schemaProviders) : List.of();
    }

    public SemanticActionPromoter(RegisteredActionCatalog catalog) {
        this(catalog, List.of());
    }

    /**
     * Validates and promotes the candidate against trusted identity and server-side
     * catalog data.
     *
     * @param candidate     untrusted candidate output (may be {@code null})
     * @param tenantContext the trusted, server-resolved request context
     * @return a success result with a fully-formed canonical action, or a failure result
     *         with non-sensitive validation errors
     */
    public SemanticActionPromotionResult promote(SemanticExtractionCandidate candidate,
                                                 TenantContext tenantContext) {
        if (candidate == null) {
            return failure(List.of("candidate is required"));
        }

        List<String> errors = new ArrayList<>();

        TenantContext trusted = validateTrustedContext(tenantContext, errors);
        String action = validateActionType(candidate.getCandidateActionType(), errors);
        String resource = validateResourceName(candidate.getCandidateTargetResource(), errors);

        Map<String, Object> parameterInput = candidate.getCandidateParameters();
        validateParameterStructure(parameterInput, errors);
        validateNoReservedParameterKeys(parameterInput, errors);

        if (trusted == null || action == null || resource == null || !errors.isEmpty()) {
            return failure(errors);
        }

        RegisteredActionTarget target;
        try {
            Optional<RegisteredActionTarget> resolved =
                    catalog.resolve(trusted.getOrganizationId(), trusted.getAgentId(), resource, action);
            if (resolved.isEmpty()) {
                errors.add("action '" + action + "' is not an explicitly registered action for resource '"
                        + resource + "' (resource missing/inactive, agent unbound, or action not permitted)");
                return failure(errors);
            }
            target = resolved.get();
        } catch (RuntimeException ex) {
            return failure(List.of("registered action catalog unavailable; failing closed"));
        }

        ActionParameterSchema schema;
        try {
            schema = findSchema(trusted.getOrganizationId(), target.resourceName(), action);
        } catch (RuntimeException ex) {
            return failure(List.of("parameter schema provider unavailable; failing closed"));
        }

        Map<String, Object> parameters = parameterInput != null ? new HashMap<>(parameterInput) : new HashMap<>();
        if (schema != null) {
            validateAgainstSchema(parameters, schema, errors);
            if (!errors.isEmpty()) {
                return failure(errors);
            }
        } else if (!parameters.isEmpty()) {
            errors.add("no parameter schema is registered for resource '" + target.resourceName()
                    + "' action '" + action + "'; supplied parameters are unverifiable and rejected");
            return failure(errors);
        }

        CanonicalAction canonicalAction = CanonicalAction.builder()
                .actionId(UUID.randomUUID().toString())
                .organizationId(trusted.getOrganizationId())
                .agentId(trusted.getAgentId())
                .userId(trusted.getUserId())
                .actionType(action)
                .targetResource(target.resourceName())
                .parameters(parameters)
                .timestamp(Instant.now())
                .build();

        return SemanticActionPromotionResult.builder()
                .successful(true)
                .canonicalAction(canonicalAction)
                .validationErrors(List.of())
                .parametersSchemaEnforced(schema != null)
                .build();
    }

    private TenantContext validateTrustedContext(TenantContext context, List<String> errors) {
        if (context == null) {
            errors.add("trusted tenant context is required");
            return null;
        }
        if (isBlank(context.getOrganizationId())) {
            errors.add("authenticated organization identity is required");
        }
        if (isBlank(context.getAgentId())) {
            errors.add("authenticated agent identity is required");
        }
        if (!"AGENT".equals(context.getPrincipalType())) {
            errors.add("semantic promotion requires an authenticated agent principal");
        }
        return errors.isEmpty() ? context : null;
    }

    private String validateActionType(String raw, List<String> errors) {
        if (isBlank(raw)) {
            errors.add("candidate action type is required");
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_ACTION_LENGTH) {
            errors.add("candidate action type exceeds maximum length");
            return null;
        }
        if (containsControlCharacters(trimmed)) {
            errors.add("candidate action type contains control characters");
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!ActionParameterRules.isWellFormedActionIdentifier(normalized)) {
            errors.add("candidate action type is not a valid action identifier");
            return null;
        }
        return normalized;
    }

    private String validateResourceName(String raw, List<String> errors) {
        if (isBlank(raw)) {
            errors.add("candidate target resource is required");
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_RESOURCE_LENGTH) {
            errors.add("candidate target resource exceeds maximum length");
            return null;
        }
        if (containsControlCharacters(trimmed)) {
            errors.add("candidate target resource contains control characters");
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!ActionParameterRules.isWellFormedResourceIdentifier(normalized)) {
            errors.add("candidate target resource is not a valid resource identifier");
            return null;
        }
        return normalized;
    }

    private void validateParameterStructure(Map<String, Object> parameters, List<String> errors) {
        if (parameters == null) {
            return;
        }
        if (parameters.size() > MAX_PARAMETER_COUNT) {
            errors.add("parameter map exceeds maximum size");
            return;
        }
        Set<String> seenKeys = new HashSet<>();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                errors.add("parameter key must not be blank");
                continue;
            }
            if (key.length() > MAX_KEY_LENGTH) {
                errors.add("parameter key exceeds maximum length");
                continue;
            }
            if (!ActionParameterRules.isWellFormedParameterKey(key)) {
                errors.add("parameter key '" + key + "' contains unsupported characters");
                continue;
            }
            if (!seenKeys.add(key.toLowerCase(Locale.ROOT))) {
                errors.add("ambiguous parameter keys differing only by case/format: '" + key + "'");
            }
            validateValue(key, entry.getValue(), 0, errors);
        }
    }

    private void validateValue(String key, Object value, int depth, List<String> errors) {
        if (depth > MAX_DEPTH) {
            errors.add("parameter '" + key + "' exceeds maximum nesting depth");
            return;
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return;
        }
        if (value instanceof String stringValue) {
            if (stringValue.length() > MAX_STRING_LENGTH) {
                errors.add("parameter '" + key + "' string value exceeds maximum length");
            }
            if (containsUnsafeControlCharacters(stringValue)) {
                errors.add("parameter '" + key + "' string value contains control characters");
            }
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> nested : mapValue.entrySet()) {
                Object nestedKey = nested.getKey();
                if (!(nestedKey instanceof String nestedKeyString)) {
                    errors.add("nested parameter key in '" + key + "' must be a string");
                    continue;
                }
                if (nestedKeyString.isBlank()
                        || !ActionParameterRules.isWellFormedParameterKey(nestedKeyString)) {
                    errors.add("nested parameter key '" + nestedKeyString
                            + "' in '" + key + "' contains unsupported characters");
                }
                validateValue(nestedKeyString, nested.getValue(), depth + 1, errors);
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            if (listValue.size() > MAX_PARAMETER_COUNT) {
                errors.add("parameter '" + key + "' array exceeds maximum size");
                return;
            }
            for (Object item : listValue) {
                validateValue(key, item, depth + 1, errors);
            }
            return;
        }
        errors.add("parameter '" + key + "' has an unsupported value type");
    }

    private void validateNoReservedParameterKeys(Map<String, Object> parameters, List<String> errors) {
        if (parameters == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            rejectReservedKey(entry.getKey(), errors);
            rejectNestedReservedKeys(entry.getValue(), errors);
        }
    }

    private void rejectNestedReservedKeys(Object value, List<String> errors) {
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> nested : mapValue.entrySet()) {
                if (nested.getKey() instanceof String nestedKey) {
                    rejectReservedKey(nestedKey, errors);
                }
                rejectNestedReservedKeys(nested.getValue(), errors);
            }
        } else if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                rejectNestedReservedKeys(item, errors);
            }
        }
    }

    private void rejectReservedKey(String key, List<String> errors) {
        if (ActionParameterRules.isReservedParameterKey(key)) {
            errors.add("parameter '" + key + "' uses a reserved identity/authorization key and is rejected");
        }
    }

    private ActionParameterSchema findSchema(String organizationId, String resourceName, String actionType) {
        for (ActionParameterSchemaProvider provider : schemaProviders) {
            Optional<ActionParameterSchema> schema = provider.schemaFor(organizationId, resourceName, actionType);
            if (schema != null && schema.isPresent()) {
                return schema.get();
            }
        }
        return null;
    }

    private void validateAgainstSchema(Map<String, Object> parameters,
                                       ActionParameterSchema schema,
                                       List<String> errors) {
        validateNodeAgainstSchema(parameters, schema, "", 0, errors);
    }

    /**
     * Recursively validates a (sub)value against a (sub)schema. The schema is trusted
     * (it was validated when stored), but every branch is still walked defensively:
     * an absent or untyped node constrains nothing, and a mismatch is always a rejection.
     */
    private void validateNodeAgainstSchema(Object value,
                                           ActionParameterSchema schema,
                                           String path,
                                           int depth,
                                           List<String> errors) {
        if (schema == null || schema.type() == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add("parameter '" + displayPath(path) + "' exceeds maximum nesting depth");
            return;
        }
        ParameterType expected = schema.type();
        if (expected == ParameterType.OBJECT) {
            if (!(value instanceof Map<?, ?> objectValue)) {
                errors.add("parameter '" + displayPath(path) + "' must be an object");
                return;
            }
            for (Map.Entry<String, ActionParameterSchema> child : schema.properties().entrySet()) {
                String key = child.getKey();
                ActionParameterSchema childSchema = child.getValue();
                if (objectValue.containsKey(key)) {
                    validateNodeAgainstSchema(objectValue.get(key), childSchema, join(path, key), depth + 1, errors);
                } else if (childSchema != null && childSchema.required()) {
                    errors.add("missing required parameter '" + join(path, key) + "'");
                }
            }
            if (!schema.additionalProperties()) {
                for (Object rawKey : objectValue.keySet()) {
                    if (rawKey instanceof String key && !schema.properties().containsKey(key)) {
                        errors.add("unsupported parameter '" + join(path, key) + "'");
                    }
                }
            }
            return;
        }
        if (expected == ParameterType.ARRAY) {
            if (!(value instanceof List<?> arrayValue)) {
                errors.add("parameter '" + displayPath(path) + "' must be an array");
                return;
            }
            ActionParameterSchema itemSchema = schema.items();
            if (itemSchema == null) {
                return;
            }
            int index = 0;
            for (Object item : arrayValue) {
                validateNodeAgainstSchema(item, itemSchema, path + "[" + index + "]", depth + 1, errors);
                index++;
            }
            return;
        }
        ParameterType actual = typeOf(value);
        if (actual != expected) {
            errors.add("parameter '" + displayPath(path) + "' has an invalid type (expected " + expected + ")");
        }
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String displayPath(String path) {
        return path.isEmpty() ? "<root>" : path;
    }

    private static ParameterType typeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return ParameterType.STRING;
        }
        if (value instanceof Boolean) {
            return ParameterType.BOOLEAN;
        }
        if (value instanceof Number) {
            return ParameterType.NUMBER;
        }
        if (value instanceof Map) {
            return ParameterType.OBJECT;
        }
        if (value instanceof List) {
            return ParameterType.ARRAY;
        }
        return null;
    }

    private static boolean containsControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsafeControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SemanticActionPromotionResult failure(List<String> errors) {
        List<String> effectiveErrors = errors.isEmpty()
                ? List.of("candidate could not be promoted")
                : List.copyOf(errors);
        return SemanticActionPromotionResult.builder()
                .successful(false)
                .canonicalAction(null)
                .validationErrors(effectiveErrors)
                .parametersSchemaEnforced(false)
                .build();
    }
}
