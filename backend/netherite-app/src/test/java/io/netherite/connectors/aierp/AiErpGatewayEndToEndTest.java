package io.netherite.connectors.aierp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.api.model.GatewayActionRequest;
import io.netherite.common.model.ConnectorType;
import io.netherite.common.model.SensitivityLevel;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.BindingDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.domain.service.ResourceDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AiErpGatewayEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private AgentDomainService agentService;

    @Autowired
    private ResourceDomainService resourceDomainService;

    @Autowired
    private BindingDomainService bindingDomainService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AiErpResourceConnector aiErpResourceConnector;

    @Autowired
    private AiErpTokenService aiErpTokenService;

    @Autowired
    private io.netherite.security.key.ResourceCredentialService resourceCredentialService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrganizationEntity organization;
    private AgentDomainService.AgentRegistrationResult registeredAgent;
    private ResourceEntity resource;
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(aiErpResourceConnector, "restTemplate", mockRestTemplate);
        ReflectionTestUtils.setField(aiErpTokenService, "restTemplate", mockRestTemplate);

        organization = organizationService.createOrganization("AI-ERP Test Org", "aierp-" + UUID.randomUUID().toString().substring(0, 8));
        registeredAgent = agentService.registerAgent(organization.getId(), "AiErpAgent", "AI-ERP Test Agent", "TIER_1");
        Map<String, String> creds = Map.of("email", "admin@erp.local", "password", "secret");
        String encrypted = resourceCredentialService.encryptForStorage(creds);
        resource = resourceDomainService.createResource(
                organization.getId(),
                "ai-erp-gateway-resource",
                ConnectorType.AI_ERP,
                "http://93.184.216.34",
                encrypted,
                SensitivityLevel.LOW,
                "AI-ERP gateway integration test resource");
    }

    @Test
    @DisplayName("Governed execution: Gateway -> Binding check -> AiErpResourceConnector -> AI-ERP")
    void testGatewayExecutionAllowed() throws Exception {
        bindingDomainService.bindAgentToResource(
                organization.getId(),
                registeredAgent.agent().getId(),
                resource.getId(),
                List.of("AI_ERP:GET_SCHEMA"));

        when(mockRestTemplate.exchange(
                eq("http://93.184.216.34/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"object\":\"test-jwt-token\"}", HttpStatus.OK));

        when(mockRestTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"entities\":[{\"name\":\"account\"}]}", HttpStatus.OK));

        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("AI_ERP:GET_SCHEMA")
                .resource("ai-erp-gateway-resource")
                .parameters(Map.of())
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.action").value("AI_ERP:GET_SCHEMA"))
                .andExpect(jsonPath("$.resource").value("ai-erp-gateway-resource"));

        var auditLogs = auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        assertThat(auditLogs).isNotEmpty();
    }

    @Test
    @DisplayName("Binding authorization: Unbound action is rejected before contacting ERP")
    void testGatewayExecutionBindingRejected() throws Exception {
        bindingDomainService.bindAgentToResource(
                organization.getId(),
                registeredAgent.agent().getId(),
                resource.getId(),
                List.of("AI_ERP:PLACE_SALES_ORDER"));

        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("AI_ERP:GET_SCHEMA")
                .resource("ai-erp-gateway-resource")
                .parameters(Map.of())
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Governed execution: Gateway -> AI_ERP:QUERY executed with parameters")
    void testGatewayQueryExecution() throws Exception {
        bindingDomainService.bindAgentToResource(
                organization.getId(),
                registeredAgent.agent().getId(),
                resource.getId(),
                List.of("AI_ERP:QUERY"));

        when(mockRestTemplate.exchange(
                eq("http://93.184.216.34/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"object\":\"test-jwt-token\"}", HttpStatus.OK));

        when(mockRestTemplate.exchange(
                eq(URI.create("http://93.184.216.34/api/v3.0/p/agent/query")),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"data\":[{\"id\":\"123\",\"name\":\"Acme Corp\"}],\"total_count\":1}", HttpStatus.OK));

        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("AI_ERP:QUERY")
                .resource("ai-erp-gateway-resource")
                .parameters(Map.of("eql", "SELECT id, name FROM account"))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.action").value("AI_ERP:QUERY"))
                .andExpect(jsonPath("$.responseBody.success").value(true));
    }

    @Test
    @DisplayName("Governed execution: Gateway -> AI_ERP:CREATE_RECORD path param interpolation")
    void testGatewayCreateRecordExecution() throws Exception {
        bindingDomainService.bindAgentToResource(
                organization.getId(),
                registeredAgent.agent().getId(),
                resource.getId(),
                List.of("AI_ERP:CREATE_RECORD"));

        when(mockRestTemplate.exchange(
                eq("http://93.184.216.34/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"object\":\"test-jwt-token\"}", HttpStatus.OK));

        when(mockRestTemplate.exchange(
                eq(URI.create("http://93.184.216.34/api/v3.0/p/agent/records/account")),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"data\":{\"id\":\"rec-999\",\"name\":\"Acme\"}}", HttpStatus.OK));

        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("AI_ERP:CREATE_RECORD")
                .resource("ai-erp-gateway-resource")
                .parameters(Map.of("entity", "account", "name", "Acme"))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.action").value("AI_ERP:CREATE_RECORD"));
    }

    @Test
    @DisplayName("Governed execution: Gateway -> AI_ERP:UPDATE_RECORD sends PATCH with path params and body")
    void testGatewayUpdateRecordExecution() throws Exception {
        String customerId = "10727423-9f10-b258-ae5b-cadc0d302f65";
        URI updateUri = URI.create(
                "http://93.184.216.34/api/v3.0/p/agent/records/customer/" + customerId);

        bindingDomainService.bindAgentToResource(
                organization.getId(),
                registeredAgent.agent().getId(),
                resource.getId(),
                List.of("AI_ERP:UPDATE_RECORD"));

        when(mockRestTemplate.exchange(
                eq("http://93.184.216.34/api/v3/en_US/auth/jwt/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"object\":\"test-jwt-token\"}", HttpStatus.OK));

        when(mockRestTemplate.exchange(
                eq(updateUri),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"success\":true,\"record\":{\"id\":\"" + customerId + "\",\"credit_limit\":9500}}",
                        HttpStatus.OK));

        GatewayActionRequest request = GatewayActionRequest.builder()
                .action("AI_ERP:UPDATE_RECORD")
                .resource("ai-erp-gateway-resource")
                .parameters(Map.of(
                        "entity", "customer",
                        "id", customerId,
                        "credit_limit", 9500))
                .build();

        mockMvc.perform(post("/v1/gateway/execute")
                        .header("X-Netherite-Agent-Key", registeredAgent.fullApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.action").value("AI_ERP:UPDATE_RECORD"))
                .andExpect(jsonPath("$.responseBody.success").value(true))
                .andExpect(jsonPath("$.responseBody.record.credit_limit").value(9500));

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(mockRestTemplate).exchange(
                eq(updateUri),
                eq(HttpMethod.PATCH),
                entityCaptor.capture(),
                eq(String.class));

        assertThat(entityCaptor.getValue().getBody()).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) entityCaptor.getValue().getBody())
                .containsEntry("credit_limit", 9500)
                .doesNotContainKeys("entity", "id");
    }
}
