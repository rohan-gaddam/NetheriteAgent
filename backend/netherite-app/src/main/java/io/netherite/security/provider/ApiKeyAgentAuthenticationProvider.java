package io.netherite.security.provider;

import io.netherite.domain.entity.AgentApiKeyEntity;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.repository.AgentApiKeyRepository;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.security.key.ApiKeyHashService;
import io.netherite.security.principal.AuthenticatedAgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Component
public class ApiKeyAgentAuthenticationProvider implements AgentAuthenticationProvider {

    public static final String HEADER_AGENT_KEY = "X-Netherite-Agent-Key";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int DUMMY_SECRET_BYTES = 32;

    private final AgentApiKeyRepository apiKeyRepository;
    private final AgentRepository agentRepository;
    private final OrganizationRepository organizationRepository;
    private final ApiKeyHashService apiKeyHashService;

    /**
     * A freshly generated, cryptographically valid BCrypt hash used to equalize
     * verification timing for unknown key identifiers. It is generated once per
     * application boot from a random secret, so it is never a static or
     * reproducible credential and always yields the same verify cost as a real hit.
     */
    private final String dummyHash;

    public ApiKeyAgentAuthenticationProvider(
            AgentApiKeyRepository apiKeyRepository,
            AgentRepository agentRepository,
            OrganizationRepository organizationRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.agentRepository = agentRepository;
        this.organizationRepository = organizationRepository;
        this.apiKeyHashService = new ApiKeyHashService();
        this.dummyHash = apiKeyHashService.hashSecret(randomSecret());
    }

    private static String randomSecret() {
        byte[] bytes = new byte[DUMMY_SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public boolean supports(HttpServletRequest request) {
        String keyHeader = request.getHeader(HEADER_AGENT_KEY);
        if (keyHeader != null && !keyHeader.isBlank()) {
            return true;
        }

        String authHeader = request.getHeader(HEADER_AUTHORIZATION);
        return authHeader != null && authHeader.startsWith(BEARER_PREFIX);
    }

    @Override
    public Optional<AuthenticatedAgentPrincipal> authenticate(HttpServletRequest request) {
        String rawKey = extractRawKey(request);
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }

        // Key format: <keyIdentifier>.<secretToken>
        int separatorIndex = rawKey.indexOf('.');
        if (separatorIndex <= 0 || separatorIndex == rawKey.length() - 1) {
            return Optional.empty();
        }

        String keyIdentifier = rawKey.substring(0, separatorIndex).trim();
        String secretToken = rawKey.substring(separatorIndex + 1).trim();

        Optional<AgentApiKeyEntity> keyOpt = apiKeyRepository.findByKeyIdentifier(keyIdentifier);
        if (keyOpt.isEmpty()) {
            // Constant-time dummy verification against a valid per-boot hash
            apiKeyHashService.verifySecret(secretToken, dummyHash);
            return Optional.empty();
        }

        AgentApiKeyEntity apiKey = keyOpt.get();

        // 1. Check revocation and expiration
        if (!apiKey.isValid()) {
            return Optional.empty();
        }

        // 2. Verify secret hash
        boolean secretMatches = apiKeyHashService.verifySecret(secretToken, apiKey.getSecretHash());
        if (!secretMatches) {
            return Optional.empty();
        }

        // 3. Verify Agent status
        Optional<AgentEntity> agentOpt = agentRepository.findByIdAndOrganizationId(apiKey.getAgentId(), apiKey.getOrganizationId());
        if (agentOpt.isEmpty() || !"ACTIVE".equalsIgnoreCase(agentOpt.get().getStatus())) {
            return Optional.empty();
        }
        AgentEntity agent = agentOpt.get();

        // 4. Verify Organization status
        Optional<OrganizationEntity> orgOpt = organizationRepository.findById(apiKey.getOrganizationId());
        if (orgOpt.isEmpty() || !"ACTIVE".equalsIgnoreCase(orgOpt.get().getStatus())) {
            return Optional.empty();
        }

        AuthenticatedAgentPrincipal principal = AuthenticatedAgentPrincipal.builder()
                .organizationId(apiKey.getOrganizationId())
                .agentId(agent.getId())
                .keyIdentifier(apiKey.getKeyIdentifier())
                .trustTier(agent.getTrustTier())
                .authenticationMechanism(getMechanismName())
                .authenticatedAt(Instant.now())
                .build();

        return Optional.of(principal);
    }

    @Override
    public String getMechanismName() {
        return "API_KEY";
    }

    private String extractRawKey(HttpServletRequest request) {
        String keyHeader = request.getHeader(HEADER_AGENT_KEY);
        if (keyHeader != null && !keyHeader.isBlank()) {
            return keyHeader.trim();
        }

        String authHeader = request.getHeader(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        return null;
    }
}
