package io.netherite.domain;

import io.netherite.common.exception.NetheriteException;
import io.netherite.common.exception.SystemErrorCode;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.AgentApiKeyEntity;
import io.netherite.domain.entity.AgentEntity;
import io.netherite.domain.entity.AgentResourceBindingEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.AgentApiKeyRepository;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.AgentResourceBindingRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiTenancyDomainIntegrationTest {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private AgentDomainService agentService;

    @Autowired
    private ResourceDomainService resourceService;

    @Autowired
    private BindingDomainService bindingService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentApiKeyRepository apiKeyRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private AgentResourceBindingRepository bindingRepository;

    private OrganizationEntity orgA;
    private OrganizationEntity orgB;

    @BeforeEach
    void setUp() {
        orgA = organizationService.createOrganization("Acme Corp", "acme-corp");
        orgB = organizationService.createOrganization("Beta Inc", "beta-inc");
    }

    @Test
    @DisplayName("1. Organization persistence and unique slug constraint")
    void testOrganizationPersistence() {
        assertThat(orgA.getId()).isNotNull();
        assertThat(orgA.getSlug()).isEqualTo("acme-corp");
        assertThat(orgA.getStatus()).isEqualTo("ACTIVE");

        // Attempting duplicate slug must be rejected
        assertThatThrownBy(() -> organizationService.createOrganization("Duplicate Acme", "acme-corp"))
                .isInstanceOf(NetheriteException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("2. User creation under organization")
    void testUserCreationUnderOrganization() {
        UserEntity user = UserEntity.builder()
                .organizationId(orgA.getId())
                .email("admin@acme.com")
                .fullName("Alice Admin")
                .status("ACTIVE")
                .build();

        UserEntity savedUser = userRepository.save(user);
        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getOrganizationId()).isEqualTo(orgA.getId());

        List<UserEntity> orgAUsers = userRepository.findAllByOrganizationId(orgA.getId());
        assertThat(orgAUsers).hasSize(1);

        List<UserEntity> orgBUsers = userRepository.findAllByOrganizationId(orgB.getId());
        assertThat(orgBUsers).isEmpty();
    }

    @Test
    @DisplayName("3. Agent registration with secure API key under organization")
    void testAgentRegistrationUnderOrganization() {
        AgentDomainService.AgentRegistrationResult result = agentService.registerAgent(
                orgA.getId(),
                "SupportBot",
                "Customer Support Agent",
                "TIER_2"
        );

        AgentEntity agent = result.agent();
        assertThat(agent.getId()).isNotNull();
        assertThat(agent.getOrganizationId()).isEqualTo(orgA.getId());
        assertThat(agent.getName()).isEqualTo("SupportBot");
        assertThat(agent.getTrustTier()).isEqualTo("TIER_2");

        assertThat(result.keyIdentifier()).startsWith("ntk_live_");
        assertThat(result.secretToken()).isNotBlank();
        assertThat(result.fullApiKey()).isEqualTo(result.keyIdentifier() + "." + result.secretToken());

        // Verify key stored in database has salted hash, NOT plaintext secret
        List<AgentApiKeyEntity> keys = apiKeyRepository.findAllByAgentIdAndOrganizationId(agent.getId(), orgA.getId());
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getSecretHash()).isNotEqualTo(result.secretToken());
        assertThat(keys.get(0).getKeyIdentifier()).isEqualTo(result.keyIdentifier());
    }

    @Test
    @DisplayName("4. Resource creation under organization")
    void testResourceCreationUnderOrganization() {
        ResourceEntity resource = resourceService.createResource(
                orgA.getId(),
                "Payment API",
                ConnectorType.HTTP_REST,
                "https://payments.acme.com/v1",
                "enc_secret_token_xyz",
                SensitivityLevel.HIGH,
                "Core payment gateway"
        );

        assertThat(resource.getId()).isNotNull();
        assertThat(resource.getOrganizationId()).isEqualTo(orgA.getId());
        assertThat(resource.getName()).isEqualTo("Payment API");
        assertThat(resource.getConnectorType()).isEqualTo(ConnectorType.HTTP_REST);
        assertThat(resource.getSensitivityLevel()).isEqualTo(SensitivityLevel.HIGH);
    }

    @Test
    @DisplayName("5. Agent-Resource binding and action authorization")
    void testAgentResourceBinding() {
        AgentDomainService.AgentRegistrationResult agentRes = agentService.registerAgent(
                orgA.getId(),
                "BillingAgent",
                "Handles refunds",
                "TIER_1"
        );
        ResourceEntity paymentResource = resourceService.createResource(
                orgA.getId(),
                "PaymentGateway",
                ConnectorType.HTTP_REST,
                "https://api.payments.com",
                null,
                SensitivityLevel.HIGH,
                "Payment processing"
        );

        AgentResourceBindingEntity binding = bindingService.bindAgentToResource(
                orgA.getId(),
                agentRes.agent().getId(),
                paymentResource.getId(),
                List.of("PAYMENT:REFUND", "PAYMENT:QUERY")
        );

        assertThat(binding.getId()).isNotNull();
        assertThat(binding.isActive()).isTrue();
        assertThat(binding.getAllowedActions()).containsExactlyInAnyOrder("PAYMENT:REFUND", "PAYMENT:QUERY");

        // Action checks
        assertThat(bindingService.isActionAuthorized(orgA.getId(), agentRes.agent().getId(), paymentResource.getId(), "PAYMENT:REFUND")).isTrue();
        assertThat(bindingService.isActionAuthorized(orgA.getId(), agentRes.agent().getId(), paymentResource.getId(), "PAYMENT:QUERY")).isTrue();
        assertThat(bindingService.isActionAuthorized(orgA.getId(), agentRes.agent().getId(), paymentResource.getId(), "PAYMENT:DELETE_ACCOUNT")).isFalse();
    }

    @Test
    @DisplayName("6. Security Boundary: Strict cross-tenant access rejection")
    void testCrossTenantAccessRejection() {
        // Create Agent and Resource in Org A
        AgentDomainService.AgentRegistrationResult agentA = agentService.registerAgent(
                orgA.getId(),
                "AgentA",
                "Org A Agent",
                "TIER_1"
        );
        ResourceEntity resourceA = resourceService.createResource(
                orgA.getId(),
                "ResourceA",
                ConnectorType.HTTP_REST,
                "https://a.com",
                null,
                SensitivityLevel.MEDIUM,
                "Resource A"
        );

        // Org B attempts to look up Org A's agent -> REJECTED
        assertThatThrownBy(() -> agentService.getAgent(orgB.getId(), agentA.agent().getId()))
                .isInstanceOf(NetheriteException.class)
                .hasFieldOrPropertyWithValue("errorCode", SystemErrorCode.ERR_RESOURCE_NOT_FOUND);

        // Org B attempts to look up Org A's resource -> REJECTED
        assertThatThrownBy(() -> resourceService.getResource(orgB.getId(), resourceA.getId()))
                .isInstanceOf(NetheriteException.class)
                .hasFieldOrPropertyWithValue("errorCode", SystemErrorCode.ERR_RESOURCE_NOT_FOUND);

        // Attempting cross-tenant binding (Org B tries to bind Org A's agent) -> REJECTED
        assertThatThrownBy(() -> bindingService.bindAgentToResource(orgB.getId(), agentA.agent().getId(), resourceA.getId(), List.of("ACTION")))
                .isInstanceOf(NetheriteException.class)
                .hasFieldOrPropertyWithValue("errorCode", SystemErrorCode.ERR_RESOURCE_NOT_FOUND);

        // List queries for Org B must be empty
        assertThat(agentService.listAgents(orgB.getId())).isEmpty();
        assertThat(resourceService.listResources(orgB.getId())).isEmpty();
    }
}
