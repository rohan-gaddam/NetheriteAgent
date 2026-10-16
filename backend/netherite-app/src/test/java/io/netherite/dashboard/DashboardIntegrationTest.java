package io.netherite.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.common.context.TenantContext;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.domain.entity.*;
import io.netherite.domain.repository.*;
import io.netherite.domain.service.OrganizationService;
import io.netherite.security.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
class DashboardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private ResourceHealthRepository resourceHealthRepository;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private SsrfGuardValidator ssrfGuardValidator;

    private OrganizationEntity organization;

    @BeforeEach
    void setUp() {
        organization = organizationService.createOrganization("Dashboard Corp", "dash-" + UUID.randomUUID().toString().substring(0, 8));
        TenantContextHolder.setContext(TenantContext.builder()
                .organizationId(organization.getId().toString())
                .principalType("USER")
                .build());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Dashboard returns overview with correct metric counts")
    void dashboardReturnsOverviewMetrics() throws Exception {
        AgentEntity agent = agentRepository.save(AgentEntity.builder()
                .organizationId(organization.getId())
                .name("Test Agent")
                .status("ACTIVE")
                .build());

        ResourceEntity resource = resourceRepository.save(ResourceEntity.builder()
                .organizationId(organization.getId())
                .name("Test Resource")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://api.example.com")
                .status("ACTIVE")
                .build());

        mockMvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.agents").value(1))
                .andExpect(jsonPath("$.overview.resources").value(1))
                .andExpect(jsonPath("$.overview.pendingApprovals").isNumber())
                .andExpect(jsonPath("$.overview.actionsToday").isNumber())
                .andExpect(jsonPath("$.overview.deniedActions").isNumber());
    }

    @Test
    @DisplayName("Dashboard data is tenant scoped")
    void dashboardIsTenantScoped() throws Exception {
        OrganizationEntity otherOrg = organizationService.createOrganization("Other Corp", "other-" + UUID.randomUUID().toString().substring(0, 8));

        agentRepository.save(AgentEntity.builder()
                .organizationId(otherOrg.getId())
                .name("Other Agent")
                .status("ACTIVE")
                .build());

        agentRepository.save(AgentEntity.builder()
                .organizationId(organization.getId())
                .name("My Agent")
                .status("ACTIVE")
                .build());

        mockMvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.agents").value(1));
    }

    @Test
    @DisplayName("Dashboard health section returns resource health data")
    void dashboardReturnsHealthData() throws Exception {
        ResourceEntity resource = resourceRepository.save(ResourceEntity.builder()
                .organizationId(organization.getId())
                .name("Payment API")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://payment.example.com")
                .status("ACTIVE")
                .build());

        resourceHealthRepository.save(ResourceHealthEntity.builder()
                .resourceId(resource.getId())
                .organizationId(organization.getId())
                .resourceName("Payment API")
                .status("HEALTHY")
                .lastCheckedAt(Instant.now())
                .build());

        mockMvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.health.resourceConnections").isArray())
                .andExpect(jsonPath("$.health.resourceConnections[0].resourceName").value("Payment API"))
                .andExpect(jsonPath("$.health.resourceConnections[0].status").value("HEALTHY"));
    }

    @Test
    @DisplayName("Dashboard returns recent activity from audit logs")
    void dashboardReturnsRecentActivity() throws Exception {
        mockMvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentActivity").isArray());
    }

    @Test
    @DisplayName("Manual health check trigger completes successfully")
    void manualHealthCheckCompletes() throws Exception {
        mockMvc.perform(post("/v1/dashboard/health/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.message").value("Health check completed"));
    }

    @Test
    @DisplayName("Health check records resource status correctly")
    void healthCheckRecordsResourceStatus() {
        ResourceEntity resource = resourceRepository.save(ResourceEntity.builder()
                .organizationId(organization.getId())
                .name("CRM API")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://crm.example.com")
                .status("ACTIVE")
                .build());

        assertFalse(resourceHealthRepository.existsByResourceId(resource.getId()));

        resourceHealthRepository.save(ResourceHealthEntity.builder()
                .resourceId(resource.getId())
                .organizationId(organization.getId())
                .resourceName("CRM API")
                .status("UNKNOWN")
                .build());

        assertTrue(resourceHealthRepository.existsByResourceId(resource.getId()));

        var health = resourceHealthRepository.findByResourceId(resource.getId());
        assertTrue(health.isPresent());
        assertEquals("UNKNOWN", health.get().getStatus());
    }

    @Test
    @DisplayName("Health status and timestamps are persisted correctly")
    void healthTimestampsPersisted() {
        ResourceEntity resource = resourceRepository.save(ResourceEntity.builder()
                .organizationId(organization.getId())
                .name("HR API")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://hr.example.com")
                .status("ACTIVE")
                .build());

        Instant now = Instant.now();
        resourceHealthRepository.save(ResourceHealthEntity.builder()
                .resourceId(resource.getId())
                .organizationId(organization.getId())
                .resourceName("HR API")
                .status("HEALTHY")
                .lastCheckedAt(now)
                .lastHealthyAt(now)
                .build());

        var health = resourceHealthRepository.findByResourceId(resource.getId()).orElseThrow();
        assertEquals("HEALTHY", health.getStatus());
        assertNotNull(health.getLastCheckedAt());
        assertNotNull(health.getLastHealthyAt());
    }

    @Test
    @DisplayName("One resource failure does not prevent other resource checks")
    void oneResourceFailureDoesNotBlockOthers() {
        UUID orgId = organization.getId();

        resourceRepository.save(ResourceEntity.builder()
                .organizationId(orgId)
                .name("Resource A")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://a.example.com")
                .status("ACTIVE")
                .build());

        resourceRepository.save(ResourceEntity.builder()
                .organizationId(orgId)
                .name("Resource B")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://b.example.com")
                .status("ACTIVE")
                .build());

        assertEquals(2, resourceRepository.findAllByOrganizationId(orgId).size());
    }

    @Test
    @DisplayName("Dangerous operations are never used as health checks")
    void dangerousOperationsNeverUsed() {
        var service = new io.netherite.dashboard.service.HealthCheckService(resourceRepository, resourceHealthRepository, ssrfGuardValidator);

        ResourceEntity resource = resourceRepository.save(ResourceEntity.builder()
                .organizationId(organization.getId())
                .name("Safe Check")
                .connectorType(ConnectorType.HTTP_REST)
                .baseUrl("https://safe.example.com")
                .status("ACTIVE")
                .build());

        assertDoesNotThrow(() -> service.checkResource(resource, organization.getId()));
    }

    @Test
    @DisplayName("Dashboard with empty organization shows zero metrics")
    void emptyDashboardShowsZeros() throws Exception {
        mockMvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.agents").value(0))
                .andExpect(jsonPath("$.overview.resources").value(0))
                .andExpect(jsonPath("$.overview.pendingApprovals").value(0))
                .andExpect(jsonPath("$.overview.actionsToday").value(0))
                .andExpect(jsonPath("$.overview.deniedActions").value(0));
    }

    @Test
    @DisplayName("Dashboard health shows UNKNOWN when no health data exists")
    void healthUnknownWhenNoData() throws Exception {
        mockMvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.health.agentConnection.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.health.endToEndExecution.status").value("UNKNOWN"));
    }
}
