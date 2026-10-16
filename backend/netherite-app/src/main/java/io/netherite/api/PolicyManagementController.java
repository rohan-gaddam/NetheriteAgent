package io.netherite.api;

import io.netherite.common.exception.NetheriteException;
import io.netherite.domain.entity.PolicyEntity;
import io.netherite.domain.entity.PolicyVersionEntity;
import io.netherite.domain.repository.PolicyRepository;
import io.netherite.domain.repository.PolicyVersionRepository;
import io.netherite.policy.push.PolicyPushService;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.netherite.api.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/policies")
@RequiredArgsConstructor
public class PolicyManagementController {

    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final PolicyPushService policyPushService;

    @PostMapping
    public ResponseEntity<?> createPolicy(@RequestBody PolicyCreateRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        PolicyEntity policy = PolicyEntity.builder()
                .organizationId(orgUuid)
                .name(request.getName())
                .description(request.getDescription())
                .status("DRAFT")
                .build();

        policy = policyRepository.save(policy);
        return ResponseEntity.ok(toPolicyResponse(policy, List.of()));
    }

    @GetMapping
    public ResponseEntity<List<PolicyResponse>> listPolicies() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<PolicyEntity> policies = policyRepository.findAllByOrganizationId(orgUuid);

        List<PolicyResponse> responses = policies.stream()
                .map(p -> {
                    List<PolicyVersionEntity> versions = policyVersionRepository.findAllByPolicyId(p.getId());
                    return toPolicyResponse(p, versions);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{policyId}")
    public ResponseEntity<?> getPolicy(@PathVariable("policyId") String policyId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID policyUuid = UUID.fromString(policyId);
            return policyRepository.findByIdAndOrganizationId(policyUuid, orgUuid)
                    .map(policy -> {
                        List<PolicyVersionEntity> versions = policyVersionRepository.findAllByPolicyId(policy.getId());
                        return ResponseEntity.ok((Object) toPolicyResponse(policy, versions));
                    })
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "POLICY_NOT_FOUND",
                            "message", "Policy not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "POLICY_NOT_FOUND",
                    "message", "Policy not found"));
        }
    }

    @PutMapping("/{policyId}")
    public ResponseEntity<?> updatePolicy(
            @PathVariable("policyId") String policyId,
            @RequestBody PolicyCreateRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID policyUuid = UUID.fromString(policyId);
            return policyRepository.findByIdAndOrganizationId(policyUuid, orgUuid)
                    .map(policy -> {
                        if (request.getName() != null) policy.setName(request.getName());
                        if (request.getDescription() != null) policy.setDescription(request.getDescription());
                        policy = policyRepository.save(policy);
                        List<PolicyVersionEntity> versions = policyVersionRepository.findAllByPolicyId(policy.getId());
                        return ResponseEntity.ok((Object) toPolicyResponse(policy, versions));
                    })
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "POLICY_NOT_FOUND",
                            "message", "Policy not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "POLICY_NOT_FOUND",
                    "message", "Policy not found"));
        }
    }

    @PostMapping("/{policyId}/versions")
    public ResponseEntity<?> createPolicyVersion(
            @PathVariable("policyId") String policyId,
            @RequestBody PolicyVersionCreateRequest request) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID policyUuid = UUID.fromString(policyId);
            return policyRepository.findByIdAndOrganizationId(policyUuid, orgUuid)
                    .map(policy -> {
                        List<PolicyVersionEntity> existing = policyVersionRepository.findAllByPolicyId(policy.getId());
                        int nextVersion = existing.stream()
                                .mapToInt(PolicyVersionEntity::getVersionNumber)
                                .max().orElse(0) + 1;

                        PolicyVersionEntity version = PolicyVersionEntity.builder()
                                .policyId(policy.getId())
                                .versionNumber(nextVersion)
                                .naturalLanguagePrompt(request.getNaturalLanguagePrompt())
                                .regoCode(request.getRegoCode())
                                .status("DRAFT")
                                .build();

                        version = policyVersionRepository.save(version);

                        return ResponseEntity.ok((Object) PolicyVersionResponse.builder()
                                .id(version.getId().toString())
                                .policyId(version.getPolicyId().toString())
                                .versionNumber(version.getVersionNumber())
                                .naturalLanguagePrompt(version.getNaturalLanguagePrompt())
                                .regoCode(version.getRegoCode())
                                .status(version.getStatus())
                                .createdAt(version.getCreatedAt())
                                .build());
                    })
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "POLICY_NOT_FOUND",
                            "message", "Policy not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "POLICY_NOT_FOUND",
                    "message", "Policy not found"));
        }
    }

    @PostMapping("/{policyId}/versions/{versionId}/activate")
    public ResponseEntity<?> activatePolicyVersion(
            @PathVariable("policyId") String policyId,
            @PathVariable("versionId") String versionId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID policyUuid = UUID.fromString(policyId);
            UUID versionUuid = UUID.fromString(versionId);

            return policyRepository.findByIdAndOrganizationId(policyUuid, orgUuid)
                    .flatMap(policy -> policyVersionRepository.findByIdAndPolicyId(versionUuid, policyUuid))
                    .map(version -> {
                        // Security-sensitive: push the version into the policy engine BEFORE
                        // flipping it ACTIVE. A rejected push aborts activation (422/503),
                        // so an ACTIVE policy version always corresponds to a policy the
                        // engine accepted. Disabled push path is a no-op by design.
                        policyPushService.pushTenantPolicy(orgUuid, version.getRegoCode());

                        version.setStatus("ACTIVE");
                        version.setActivatedAt(Instant.now());
                        policyVersionRepository.save(version);

                        PolicyEntity policy = policyRepository.findById(version.getPolicyId()).orElseThrow();
                        policy.setActiveVersionId(version.getId());
                        policy.setStatus("ACTIVE");
                        policyRepository.save(policy);

                        return ResponseEntity.ok((Object) PolicyVersionResponse.builder()
                                .id(version.getId().toString())
                                .policyId(version.getPolicyId().toString())
                                .versionNumber(version.getVersionNumber())
                                .naturalLanguagePrompt(version.getNaturalLanguagePrompt())
                                .regoCode(version.getRegoCode())
                                .status(version.getStatus())
                                .createdAt(version.getCreatedAt())
                                .activatedAt(version.getActivatedAt())
                                .build());
                    })
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "POLICY_VERSION_NOT_FOUND",
                            "message", "Policy or version not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "POLICY_NOT_FOUND",
                    "message", "Policy not found"));
        }
    }

    private PolicyResponse toPolicyResponse(PolicyEntity policy, List<PolicyVersionEntity> versions) {
        List<PolicyVersionResponse> versionResponses = versions.stream()
                .map(v -> PolicyVersionResponse.builder()
                        .id(v.getId().toString())
                        .policyId(v.getPolicyId().toString())
                        .versionNumber(v.getVersionNumber())
                        .naturalLanguagePrompt(v.getNaturalLanguagePrompt())
                        .regoCode(v.getRegoCode())
                        .status(v.getStatus())
                        .createdAt(v.getCreatedAt())
                        .activatedAt(v.getActivatedAt())
                        .build())
                .collect(Collectors.toList());

        return PolicyResponse.builder()
                .id(policy.getId().toString())
                .organizationId(policy.getOrganizationId().toString())
                .name(policy.getName())
                .description(policy.getDescription())
                .status(policy.getStatus())
                .activeVersionId(policy.getActiveVersionId() != null ? policy.getActiveVersionId().toString() : null)
                .versions(versionResponses)
                .createdAt(policy.getCreatedAt())
                .updatedAt(policy.getUpdatedAt())
                .build();
    }
}
