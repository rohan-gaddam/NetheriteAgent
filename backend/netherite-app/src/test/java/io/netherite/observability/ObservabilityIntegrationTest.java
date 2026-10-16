package io.netherite.observability;

import com.jayway.jsonpath.JsonPath;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.testsupport.DesignatedPersonFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS8 — end-to-end observability contract over the HTTP boundary.
 *
 * <p>Asserts the correlation header and error-body correlation id are present on every
 * response class (including rejections and 404s), and that the secured /actuator/prometheus
 * endpoint serves only bounded, secret-free metrics with discovery turned off.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoints.web.discovery.enabled=false",
        // The shared test environment disables registry exports by default
        // (management.defaults.metrics.export.enabled=false); this focused WS8 suite
        // re-enables the Prometheus scrape registry it is contractually testing.
        "management.prometheus.metrics.export.enabled=true"
})
@Transactional
class ObservabilityIntegrationTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired(required = false) private PrometheusMeterRegistry prometheusMeterRegistry;

    private DesignatedPersonFixture fixture;
    private Cookie designatedCookie;

    @BeforeEach
    void setUp() {
        fixture = new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
        OrganizationEntity org = fixture.newOrganization("Observability Corp");
        UserEntity person = fixture.newMember(org, "ws8-admin");
        fixture.designate(org, person);
        designatedCookie = fixture.sessionFor(person, "ws8-admin");
    }

    @Test
    void errorBodiesCarryTheCorrelationIdAndEchoHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/ws8-404-probe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ERR_NOT_FOUND"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String requestIdInBody = JsonPath.<String>read(body, "$.requestId");
        String header = result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);

        assertThat(requestIdInBody).matches(UUID_PATTERN.asPredicate());
        assertThat(header).matches(UUID_PATTERN.asPredicate());
        assertThat(requestIdInBody).isEqualTo(header);
    }

    @Test
    void rejectionsAndSuccessesBothEchoTheCorrelationHeader() throws Exception {
        MvcResult rejection = mockMvc.perform(get("/v1/agents")
                        .header("X-Netherite-Agent-Key", "ntk_live_ws8.canary-secret"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(rejection.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME))
                .matches(UUID_PATTERN.asPredicate());

        MvcResult success = mockMvc.perform(get("/actuator/health"))
                .andReturn();
        assertThat(success.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME))
                .matches(UUID_PATTERN.asPredicate());
    }

    @Test
    void clientSuppliedCorrelationIdIsPreservedAndEchoed() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")
                        .header(CorrelationIdFilter.HEADER_NAME, "edge-trace_1.0-7"))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo("edge-trace_1.0-7");
    }

    @Test
    void prometheusRegistryIsExported() {
        assertThat(prometheusMeterRegistry).isNotNull();
    }

    @Test
    void prometheusEndpointIsGatedAndServesBoundedSecretFreeMetrics() throws Exception {
        // Without a human session the metrics endpoint stays gated (Batch F contract).
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        // Seed a bounded security counter through a real HTTP rejection.
        mockMvc.perform(get("/v1/agents").header("X-Netherite-Agent-Key", "ntk_live_ws8.bad"))
                .andExpect(status().isUnauthorized());

        // Core metrics endpoint is in the same exposure set and must be reachable to
        // an authorized person (Batch F contract), isolating the Prometheus scrape path.
        mockMvc.perform(get("/actuator/metrics").cookie(designatedCookie))
                .andExpect(status().isOk());

        MvcResult metrics = mockMvc.perform(get("/actuator/prometheus").cookie(designatedCookie))
                .andExpect(status().isOk())
                .andReturn();

        String prometheus = metrics.getResponse().getContentAsString();
        assertThat(prometheus).contains("netherite_security_auth_failures_total");
        assertThat(prometheus).contains("mechanism=\"agent_api_key\"");
        assertThat(prometheus).contains("jvm_memory_used_bytes");
        // No raw credentials or identifiers may ever reach the metrics exposition.
        assertThat(prometheus).doesNotContain("ntk_live_", "canary-secret", "ws8-admin@");
    }

    @Test
    void actuatorDiscoveryIsDisabled() throws Exception {
        MvcResult index = mockMvc.perform(get("/actuator").cookie(designatedCookie))
                .andReturn();

        // With discovery disabled the index must not enumerate endpoints.
        assertThat(index.getResponse().getStatus()).isIn(200, 404);
        assertThat(index.getResponse().getContentAsString())
                .doesNotContain("/prometheus")
                .doesNotContain("/health")
                .doesNotContain("/beans");
    }
}