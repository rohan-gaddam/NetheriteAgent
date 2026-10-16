package io.netherite.securitysuite;

import io.netherite.domain.entity.ApprovalRequestEntity;
import io.netherite.domain.entity.OrganizationEntity;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.domain.repository.UserSessionRepository;
import io.netherite.domain.service.AgentDomainService;
import io.netherite.domain.service.OrganizationService;
import io.netherite.testsupport.DesignatedPersonFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS6 â€” dedicated security suite for the approval decision boundary.
 *
 * <p>Covers: cross-tenant isolation (a ticket must be invisible to another
 * organization's authorized person), the machine path (API keys never approve),
 * and the fact that approval authority belongs to the organization's single
 * designated authorized person.</p>
 *
 * <p><b>Change from the pre-WS6 suite.</b> APR-3 previously asserted that a
 * privileged-but-unapproved session passes the filter and is then rejected by
 * the controller's separate approver gate. That second gate no longer exists —
 * approval authority <em>is</em> the designation — so the test is preserved in
 * the form that still expresses a real security property: <em>an ordinary
 * member of the organization who is not its designated person cannot
 * approve</em>. APR-4 is preserved almost unchanged — the rival organization's
 * designated person still passes authorization for their <em>own</em> tenant and
 * is still blocked by tenant scoping on the ticket, which is the actual
 * isolation guarantee.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalSecurityBoundaryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationService organizationService;
    @Autowired private AgentDomainService agentDomainService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private OrganizationEntity organization;
    private OrganizationEntity otherOrganization;
    private AgentDomainService.AgentRegistrationResult agent;
    private String agentKey;
    private ApprovalRequestEntity pendingTicket;

    /** Shared designated-person fixture: the only place WS6 suites mint people. */
    private DesignatedPersonFixture fixture;

    /** The owning organization's designated authorized person. */
    private Cookie designatedCookie;

    /** An ordinary member of the owning organization. */
    private Cookie plainMemberCookie;

    /** The rival organization's designated authorized person. */
    private Cookie otherOrgDesignatedCookie;

    @BeforeEach
    void setUp() {
        fixture = new DesignatedPersonFixture(organizationRepository, userRepository, userSessionRepository);
        organization = organizationService.createOrganization(
                "Approval Corp", "apr-" + UUID.randomUUID().toString().substring(0, 8));
        otherOrganization = organizationService.createOrganization(
                "Rival Corp", "riv-" + UUID.randomUUID().toString().substring(0, 8));
        agent = agentDomainService.registerAgent(
                organization.getId(), "ApprovalAgent", "Approval boundary agent", "TIER_1");
        agentKey = agent.fullApiKey();

        pendingTicket = approvalRequestRepository.saveAndFlush(ApprovalRequestEntity.builder()
                .organizationId(organization.getId())
                .agentId(agent.agent().getId())
                .actionType("PAYMENT:REFUND")
                .targetResource("NO_SUCH_RESOURCE_EXISTS")
                .canonicalAction(Map.of("actionType", "PAYMENT:REFUND", "targetResource", "NO_SUCH_RESOURCE_EXISTS"))
                .decisionContext(Map.of("id", UUID.randomUUID().toString()))
                .triggeredPolicies(List.of("base-policy"))
                .status("PENDING")
                .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .build());

        UserEntity designated = fixture.newMember(organization, "designated");
        fixture.designate(organization, designated);
        designatedCookie = fixture.sessionFor(designated, "designated");

        // Same organization, not designated: must not be able to decide.
        plainMemberCookie = fixture.sessionFor(fixture.newMember(organization, "plain"), "plain");

        // Rival organization's designated person: authorized for their own
        // tenant, but must still be unable to reach this ticket.
        UserEntity rival = fixture.newMember(otherOrganization, "rival");
        fixture.designate(otherOrganization, rival);
        otherOrgDesignatedCookie = fixture.sessionFor(rival, "rival");
    }

    private String ticketId() {
        return pendingTicket.getId().toString();
    }

    @Test
    @DisplayName("APR-1. Anonymous actor cannot approve a ticket")
    void anonymousCannotApprove() throws Exception {
        mockMvc.perform(post("/v1/approvals/{id}/approve", ticketId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("APR-2. Agent API key cannot approve a ticket")
    void agentKeyCannotApprove() throws Exception {
        mockMvc.perform(post("/v1/approvals/{id}/approve", ticketId())
                        .header("X-Netherite-Agent-Key", agentKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("APR-3. A non-designated member of the organization cannot approve")
    void nonDesignatedMemberCannotApprove() throws Exception {
        mockMvc.perform(post("/v1/approvals/{id}/approve", ticketId())
                        .cookie(plainMemberCookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ERR_FORBIDDEN"));
    }

    @Test
    @DisplayName("APR-3b. A non-designated member cannot read the approval queue either")
    void nonDesignatedMemberCannotReadQueue() throws Exception {
        mockMvc.perform(get("/v1/approvals").cookie(plainMemberCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/approvals/{id}", ticketId()).cookie(plainMemberCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("APR-4. Another organization's designated person cannot see or approve the ticket")
    void crossTenantApproverCannotApprove() throws Exception {
        // The rival's designated person IS authorized â€” for their own tenant.
        // Tenant scoping on the ticket, not the designation, is what stops them.
        mockMvc.perform(post("/v1/approvals/{id}/approve", ticketId())
                        .cookie(otherOrgDesignatedCookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ERR_RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/v1/approvals/{id}", ticketId())
                        .cookie(otherOrgDesignatedCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"));

        // The denial must not have mutated the victim ticket: it is still PENDING for
        // the owning organization's designated person.
        mockMvc.perform(get("/v1/approvals/{id}", ticketId()).cookie(designatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("APR-5. The designated person reads and approves a pending ticket")
    void designatedPersonReadsAndApproves() throws Exception {
        mockMvc.perform(get("/v1/approvals/{id}", ticketId()).cookie(designatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/v1/approvals/{id}/approve", ticketId())
                        .cookie(designatedCookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/approvals/{id}", ticketId()).cookie(designatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("APR-6. An organization with no designated person can decide no approvals")
    void undesignatedOrganizationCannotApprove() throws Exception {
        fixture.clearDesignation(organization);

        mockMvc.perform(post("/v1/approvals/{id}/approve", ticketId())
                        .cookie(designatedCookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
