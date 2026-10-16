package io.netherite.dashboard.controller;

import io.netherite.dashboard.service.DashboardService;
import io.netherite.dashboard.service.HealthCheckService;
import io.netherite.dashboard.service.ResourceHealthScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final HealthCheckService healthCheckService;
    private final ResourceHealthScheduler resourceHealthScheduler;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboardSummary());
    }

    @PostMapping("/health/check")
    public ResponseEntity<Map<String, String>> triggerHealthCheck() {
        resourceHealthScheduler.runHealthCheckNow();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "Health check completed"));
    }
}
