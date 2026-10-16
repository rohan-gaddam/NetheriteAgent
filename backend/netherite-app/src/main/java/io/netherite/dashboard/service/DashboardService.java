package io.netherite.dashboard.service;

import io.netherite.domain.entity.ResourceHealthEntity;
import io.netherite.domain.repository.AgentRepository;
import io.netherite.domain.repository.ApprovalRequestRepository;
import io.netherite.domain.repository.AuditLogRepository;
import io.netherite.domain.repository.ResourceHealthRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final AgentRepository agentRepository;
    private final ResourceRepository resourceRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final AuditLogRepository auditLogRepository;
    private final ResourceHealthRepository resourceHealthRepository;

    public Map<String, Object> getDashboardSummary() {
        UUID orgId = UUID.fromString(TenantContextHolder.getRequiredOrganizationId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", getOverview(orgId));
        result.put("health", getHealthSummary(orgId));
        result.put("recentActivity", getRecentActivity(orgId));
        return result;
    }

    private Map<String, Object> getOverview(UUID orgId) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("agents", agentRepository.findAllByOrganizationId(orgId).size());
        overview.put("resources", resourceRepository.findAllByOrganizationId(orgId).size());
        overview.put("pendingApprovals",
                approvalRequestRepository.findAllByOrganizationIdAndStatus(orgId, "PENDING").size());

        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();

        overview.put("actionsToday",
                auditLogRepository.countByOrganizationIdAndCreatedAtAfter(orgId, startOfDay));
        overview.put("deniedActions",
                auditLogRepository.countByOrganizationIdAndCreatedAtAfterAndDecision(orgId, startOfDay, "DENY"));

        return overview;
    }

    private Map<String, Object> getHealthSummary(UUID orgId) {
        Map<String, Object> health = new LinkedHashMap<>();

        List<ResourceHealthEntity> resourceHealthList =
                resourceHealthRepository.findAllByOrganizationIdOrderByResourceName(orgId);

        boolean anyHealthy = resourceHealthList.stream()
                .anyMatch(r -> "HEALTHY".equals(r.getStatus()));
        boolean anyProblem = resourceHealthList.stream()
                .anyMatch(r -> "PROBLEM".equals(r.getStatus()));

        Instant oneHourAgo = Instant.now().minusSeconds(3600);

        String agentConnection;
        long agentCount = agentRepository.findAllByOrganizationId(orgId).stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .count();
        if (agentCount > 0) {
            long agentsWithRecentActivity = auditLogRepository
                    .findDistinctAgentIdsWithActivitySince(orgId, oneHourAgo)
                    .size();
            agentConnection = agentsWithRecentActivity > 0 ? "HEALTHY" : "UNKNOWN";
        } else {
            agentConnection = "UNKNOWN";
        }

        String resourceConnection;
        if (resourceHealthList.isEmpty()) {
            resourceConnection = "UNKNOWN";
        } else if (anyProblem) {
            resourceConnection = "PROBLEM";
        } else if (anyHealthy) {
            resourceConnection = "HEALTHY";
        } else {
            resourceConnection = "UNKNOWN";
        }

        String endToEnd;
        long recentSuccess = auditLogRepository.countByOrganizationIdAndCreatedAtAfterAndEventType(
                orgId, oneHourAgo, "EXECUTION_SUCCEEDED");
        long recentFailure = auditLogRepository.countByOrganizationIdAndCreatedAtAfterAndEventType(
                orgId, oneHourAgo, "EXECUTION_FAILED");

        if (recentSuccess > 0 && recentFailure == 0) {
            endToEnd = "HEALTHY";
        } else if (recentFailure > 0) {
            endToEnd = "PROBLEM";
        } else {
            endToEnd = "UNKNOWN";
        }

        health.put("agentConnection", Map.of(
                "status", agentConnection,
                "description", agentConnectionDescription(agentConnection)));
        health.put("resourceConnections", resourceHealthList.stream()
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("resourceId", r.getResourceId().toString());
                    item.put("resourceName", r.getResourceName());
                    item.put("status", r.getStatus());
                    item.put("failureReason", r.getFailureReason() != null ? r.getFailureReason() : "");
                    item.put("lastCheckedAt", r.getLastCheckedAt() != null ? r.getLastCheckedAt().toString() : null);
                    item.put("lastHealthyAt", r.getLastHealthyAt() != null ? r.getLastHealthyAt().toString() : null);
                    item.put("lastFailureAt", r.getLastFailureAt() != null ? r.getLastFailureAt().toString() : null);
                    return item;
                })
                .toList());
        health.put("endToEndExecution", Map.of(
                "status", endToEnd,
                "description", endToEndDescription(endToEnd)));

        Instant lastChecked = resourceHealthList.stream()
                .map(ResourceHealthEntity::getLastCheckedAt)
                .filter(t -> t != null)
                .max(Instant::compareTo)
                .orElse(null);
        health.put("lastCheckedAt", lastChecked != null ? lastChecked.toString() : null);

        return health;
    }

    private List<Map<String, Object>> getRecentActivity(UUID orgId) {
        var recent = auditLogRepository.findTop10ByOrganizationId(orgId, PageRequest.of(0, 10));

        return recent.stream()
                .map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", a.getId().toString());
                    item.put("actionType", a.getActionType());
                    item.put("targetResource", a.getTargetResource());
                    item.put("decision", a.getDecision());
                    item.put("eventType", a.getEventType());
                    item.put("agentId", a.getAgentId() != null ? a.getAgentId().toString() : null);
                    item.put("executionDurationMs", a.getExecutionDurationMs());
                    item.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
                    return item;
                })
                .toList();
    }

    private String agentConnectionDescription(String status) {
        return switch (status) {
            case "HEALTHY" -> "Agents are communicating with NetheriteAgent";
            case "PROBLEM" -> "Some agents may have connectivity issues";
            default -> "No recent agent activity to evaluate";
        };
    }

    private String endToEndDescription(String status) {
        return switch (status) {
            case "HEALTHY" -> "Recent executions completed successfully";
            case "PROBLEM" -> "Some recent executions encountered failures";
            default -> "No recent execution activity to evaluate";
        };
    }
}
