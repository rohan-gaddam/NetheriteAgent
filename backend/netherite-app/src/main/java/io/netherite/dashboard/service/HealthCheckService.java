package io.netherite.dashboard.service;

import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.domain.entity.ResourceEntity;
import io.netherite.domain.entity.ResourceHealthEntity;
import io.netherite.domain.repository.ResourceHealthRepository;
import io.netherite.domain.repository.ResourceRepository;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class HealthCheckService {

    private final ResourceRepository resourceRepository;
    private final ResourceHealthRepository resourceHealthRepository;
    private final SsrfGuardValidator ssrfGuardValidator;
    private final RestTemplate restTemplate;
    private final ConcurrentHashMap<UUID, Boolean> activeChecks = new ConcurrentHashMap<>();

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final String HEALTHY = "HEALTHY";
    private static final String PROBLEM = "PROBLEM";
    private static final String UNKNOWN = "UNKNOWN";

    public HealthCheckService(ResourceRepository resourceRepository,
                              ResourceHealthRepository resourceHealthRepository,
                              SsrfGuardValidator ssrfGuardValidator) {
        this.resourceRepository = resourceRepository;
        this.resourceHealthRepository = resourceHealthRepository;
        this.ssrfGuardValidator = ssrfGuardValidator;

        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CHECK_TIMEOUT.toMillis());
        factory.setReadTimeout((int) CHECK_TIMEOUT.toMillis());
        // A redirect from a registered resource must never be followed to a different host
        this.restTemplate = new RestTemplate(factory);
    }

    public void checkAllResources(UUID organizationId) {
        List<ResourceEntity> resources = resourceRepository.findAllByOrganizationId(organizationId);

        for (ResourceEntity resource : resources) {
            if (activeChecks.putIfAbsent(resource.getId(), true) == null) {
                try {
                    checkResource(resource, organizationId);
                } catch (Exception e) {
                    log.error("Health check failed for resource {}: {}", resource.getName(), e.getMessage());
                    recordHealth(resource, organizationId, PROBLEM, "Health check error", "SCHEDULED");
                } finally {
                    activeChecks.remove(resource.getId());
                }
            }
        }
    }

    public void checkResource(ResourceEntity resource, UUID organizationId) {
        String baseUrl = resource.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            recordHealth(resource, organizationId, UNKNOWN, "No base URL configured", "SCHEDULED");
            return;
        }

        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            recordHealth(resource, organizationId, PROBLEM, "Invalid base URL", "SCHEDULED");
            return;
        }

        // SSRF guard — no health check may ever be dispatched to private RFC1918,
        // loopback, link-local or cloud-metadata destinations.
        if (!ssrfGuardValidator.isUriPermitted(baseUri)) {
            log.warn("Health check blocked by SSRF guard for resource '{}': {}", resource.getName(), baseUrl);
            recordHealth(resource, organizationId, PROBLEM, "SSRF guard blocked", "SCHEDULED");
            return;
        }

        try {
            restTemplate.headForHeaders(baseUrl);
            recordHealth(resource, organizationId, HEALTHY, null, "SCHEDULED");
            log.debug("Health check passed for resource '{}'", resource.getName());
        } catch (Exception e) {
            String reason = simplifyError(e);
            recordHealth(resource, organizationId, PROBLEM, reason, "SCHEDULED");
            log.debug("Health check failed for resource '{}': {}", resource.getName(), reason);
        }
    }

    public void recordExecutionFailure(UUID resourceId, UUID organizationId, String resourceName, String reason) {
        Optional<ResourceHealthEntity> existing = resourceHealthRepository.findByResourceId(resourceId);

        ResourceHealthEntity entity = existing.orElseGet(() -> ResourceHealthEntity.builder()
                .resourceId(resourceId)
                .organizationId(organizationId)
                .resourceName(resourceName)
                .build());

        entity.setStatus(PROBLEM);
        entity.setFailureReason(reason);
        entity.setLastCheckedAt(Instant.now());
        entity.setLastFailureAt(Instant.now());
        entity.setCheckSource("EXECUTION");
        entity.setUpdatedAt(Instant.now());

        resourceHealthRepository.save(entity);
    }

    public void recordExecutionSuccess(UUID resourceId, UUID organizationId, String resourceName) {
        Optional<ResourceHealthEntity> existing = resourceHealthRepository.findByResourceId(resourceId);

        ResourceHealthEntity entity = existing.orElseGet(() -> ResourceHealthEntity.builder()
                .resourceId(resourceId)
                .organizationId(organizationId)
                .resourceName(resourceName)
                .build());

        entity.setStatus(HEALTHY);
        entity.setFailureReason(null);
        entity.setLastCheckedAt(Instant.now());
        entity.setLastHealthyAt(Instant.now());
        entity.setCheckSource("EXECUTION");
        entity.setUpdatedAt(Instant.now());

        resourceHealthRepository.save(entity);
    }

    private void recordHealth(ResourceEntity resource, UUID organizationId, String status,
                              String failureReason, String checkSource) {
        Instant now = Instant.now();
        Optional<ResourceHealthEntity> existing = resourceHealthRepository.findByResourceId(resource.getId());

        ResourceHealthEntity entity = existing.orElseGet(() -> ResourceHealthEntity.builder()
                .resourceId(resource.getId())
                .organizationId(organizationId)
                .resourceName(resource.getName())
                .build());

        entity.setStatus(status);
        entity.setFailureReason(failureReason);
        entity.setLastCheckedAt(now);
        entity.setCheckSource(checkSource);

        if (HEALTHY.equals(status)) {
            entity.setLastHealthyAt(now);
        } else if (PROBLEM.equals(status)) {
            entity.setLastFailureAt(now);
        }

        resourceHealthRepository.save(entity);
    }

    private String simplifyError(Exception e) {
        String message = e.getMessage();
        if (message == null) return "Unknown error";

        if (message.contains("timed out") || message.contains("Timeout") || message.contains("timeout")) {
            return "Connection timeout";
        }
        if (message.contains("Connection refused") || message.contains("connect")) {
            return "Unable to connect";
        }
        if (message.contains("UnknownHost") || message.contains("DNS")) {
            return "Unable to resolve host";
        }
        if (message.contains("401") || message.contains("403") || message.contains("Unauthorized")) {
            return "Authentication failure";
        }
        if (message.contains("503") || message.contains("Service Unavailable")) {
            return "Service temporarily unavailable";
        }
        if (message.contains("SSL") || message.contains("TLS") || message.contains("certificate")) {
            return "SSL/TLS error";
        }

        return "Connection error";
    }
}
