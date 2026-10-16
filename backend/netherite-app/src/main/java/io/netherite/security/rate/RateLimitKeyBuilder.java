package io.netherite.security.rate;

import io.netherite.auth.session.SessionTokenHasher;

/**
 * Deterministic, bounded construction of Redis rate-limit keys.
 *
 * <p>Key grammar: {@code netherite:rl:<route>:<dimension>:<value>}. Account identifiers are
 * reduced to a SHA-256 digest so no PII ever reaches Redis keys or logs; API-key identifiers are
 * likewise hashed; session tokens, secrets and passwords never appear in any key. The delivered
 * key space is bounded by construction because every key carries a TTL equal to its window.</p>
 */
public final class RateLimitKeyBuilder {

    private static final String ROOT_PREFIX = "netherite:rl:";

    private final String accountValue;
    private final String accountIpValue;
    private final String ipValue;
    private final String agentKeyValue;
    private final String organizationValue;
    private final String userValue;
    private final String agentValue;

    private RateLimitKeyBuilder(String accountValue, String accountIpValue, String ipValue,
                                String agentKeyValue, String organizationValue,
                                String userValue, String agentValue) {
        this.accountValue = accountValue;
        this.accountIpValue = accountIpValue;
        this.ipValue = ipValue;
        this.agentKeyValue = agentKeyValue;
        this.organizationValue = organizationValue;
        this.userValue = userValue;
        this.agentValue = agentValue;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String bucketKey(RateLimitProperties.RouteSpec route, RateLimitDimension dimension) {
        String value = valueFor(dimension);
        if (value == null || value.isBlank()) {
            return null;
        }
        return ROOT_PREFIX + normalizeRoute(route.getName()) + ":" + dimension.label() + ":" + value;
    }

    private String valueFor(RateLimitDimension dimension) {
        return switch (dimension) {
            case IP -> ipValue;
            case LOGIN_ACCOUNT_IP -> accountIpValue;
            case LOGIN_ACCOUNT -> accountValue;
            case AGENT_KEY_IDENTIFIER -> agentKeyValue;
            case ORGANIZATION -> organizationValue;
            case USER -> userValue;
            case AGENT -> agentValue;
        };
    }

    private static String normalizeRoute(String name) {
        return name == null ? "route" : name.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    public static final class Builder {
        private String account;
        private String ip;
        private String agentKeyIdentifier;
        private String organizationId;
        private String userId;
        private String agentId;

        public Builder account(String account) {
            this.account = account;
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public Builder agentKeyIdentifier(String agentKeyIdentifier) {
            this.agentKeyIdentifier = agentKeyIdentifier;
            return this;
        }

        public Builder organizationId(String organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public RateLimitKeyBuilder build() {
            String normalizedAccount = normalizeAccount(account);
            String accountHash = hashIfPresent(normalizedAccount);
            String accountIp = account == null || account.isBlank() || ip == null || ip.isBlank()
                    ? null
                    : accountHash + ":" + ip;
            return new RateLimitKeyBuilder(
                    accountHash,
                    accountIp,
                    ip,
                    hashIfPresent(agentKeyIdentifier),
                    organizationId,
                    userId,
                    agentId);
        }

        private static String hashIfPresent(String raw) {
            return raw == null || raw.isBlank() ? null : SessionTokenHasher.hash(raw);
        }

        private static String normalizeAccount(String account) {
            return account == null ? null : account.trim().toLowerCase();
        }
    }
}