package io.netherite.ingestion.promotion;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single source of truth for the deterministic identifier and parameter-key rules
 * shared by semantic action promotion and registered parameter-schema validation.
 *
 * <p>Both paths — validating an untrusted {@code SemanticExtractionCandidate}'s
 * parameters and validating an operator-submitted schema definition — must agree on
 * exactly which keys are well-formed and which are reserved. Keeping one class is
 * what prevents the two rule sets from drifting apart.</p>
 */
public final class ActionParameterRules {

    public static final int MAX_ACTION_LENGTH = 128;
    public static final int MAX_RESOURCE_LENGTH = 256;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_SCHEMA_DEPTH = 6;
    public static final int MAX_SCHEMA_PROPERTIES = 256;

    private static final Pattern ACTION_PATTERN = Pattern.compile("^[A-Z0-9]+(?:[:_-][A-Z0-9]+)*$");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]*$");
    private static final Pattern PARAMETER_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");

    /**
     * Names that can influence identity or authorization. Compared after lower-casing
     * and stripping {@code _}, {@code -} and {@code .} so that {@code organization_id},
     * {@code organizationId} and {@code ORGANIZATION-ID} all collide.
     */
    private static final Set<String> RESERVED_PARAMETER_KEYS = Set.of(
            "organizationid", "orgid", "agentid", "userid", "principaltype",
            "tenant", "tenantid",
            "decision", "decisiontype", "approved", "approval", "approvedby",
            "authorization", "authorisation", "authorized", "authorised",
            "role", "roles", "permission", "permissions", "privilege", "privileges",
            "admin", "isadmin", "superuser", "root", "override", "bypass", "system");

    private ActionParameterRules() {
    }

    /** True when a non-null, control-character-free string matches the action identifier grammar. */
    public static boolean isWellFormedActionIdentifier(String candidate) {
        return candidate != null && ACTION_PATTERN.matcher(candidate).matches();
    }

    /** True when a non-null, control-character-free string matches the resource identifier grammar. */
    public static boolean isWellFormedResourceIdentifier(String candidate) {
        return candidate != null && RESOURCE_PATTERN.matcher(candidate).matches();
    }

    /** True when a key matches the parameter-key grammar (length is checked separately). */
    public static boolean isWellFormedParameterKey(String key) {
        return key != null && PARAMETER_KEY_PATTERN.matcher(key).matches();
    }

    /** True when a key collides with a reserved identity/authorization name after normalization. */
    public static boolean isReservedParameterKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(".", "");
        return RESERVED_PARAMETER_KEYS.contains(normalized);
    }

    /**
     * Normalizes a raw action identifier for storage: trims, upper-cases and rejects
     * blank, over-long, control-character-bearing or malformed values.
     *
     * @return the canonical upper-cased action identifier, or {@code null} when invalid
     */
    public static String normalizeActionIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_ACTION_LENGTH || containsControlCharacter(trimmed)) {
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        return isWellFormedActionIdentifier(normalized) ? normalized : null;
    }

    /** True when the string contains any C0/C1 control character. */
    public static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
