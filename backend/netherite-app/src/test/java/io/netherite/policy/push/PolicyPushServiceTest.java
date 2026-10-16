package io.netherite.policy.push;

import io.netherite.common.exception.NetheriteException;
import io.netherite.domain.entity.PolicyEntity;
import io.netherite.domain.entity.PolicyVersionEntity;
import io.netherite.domain.repository.PolicyRepository;
import io.netherite.domain.repository.PolicyVersionRepository;
import io.netherite.policy.push.PolicyPushProperties;
import io.netherite.policy.push.PolicyPushService;
import io.netherite.policy.push.PolicySyncStatus;
import io.netherite.policy.spi.OpaPolicyManageClient;
import io.netherite.policy.spi.OpaPolicyEngineClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyPushServiceTest {

    private final OpaPolicyManageClient manageClient = mock(OpaPolicyManageClient.class);
    private final OpaPolicyEngineClient engineClient = mock(OpaPolicyEngineClient.class);
    private final PolicyPushProperties properties = new PolicyPushProperties();
    private final PolicySyncStatus syncStatus = new PolicySyncStatus();
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final PolicyVersionRepository policyVersionRepository = mock(PolicyVersionRepository.class);

    private PolicyPushService service() {
        return new PolicyPushService(manageClient, engineClient, properties, syncStatus,
                policyRepository, policyVersionRepository);
    }

    @Test
    void pinsTenantModuleToReservedPackageWhenSourceHasNone() {
        String pinned = service().pinTenantModule(UUID.fromString("11111111-1111-1111-1111-111111111111"), "import rego.v1\n\ndefault decision := \"DENY\"");
        assertThat(pinned).startsWith("package netherite.authz.tenants.org_11111111_1111_1111_1111_111111111111\n\nimport rego.v1");
    }

    @Test
    void overridesAnyAuthorPublishedPackageWithThePinnedTenantPackage() {
        String pinned = service().pinTenantModule(UUID.randomUUID(), "package some.random.org\n\ndefault decision := \"DENY\"");
        assertThat(pinned).startsWith("package netherite.authz.tenants.org_");
        assertThat(pinned).doesNotContain("package some.random.org");
    }

    @Test
    void rejectsSystemPackageInjection() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> service().pinTenantModule(org, "package system.authz\n\ndefault allow := false"))
                .isInstanceOf(NetheriteException.class)
                .extracting(e -> ((NetheriteException) e).getErrorCode())
                .isEqualTo(io.netherite.common.exception.SystemErrorCode.ERR_POLICY_PUSH_REJECTED);
    }

    @Test
    void rejectsBlankSource() {
        assertThatThrownBy(() -> service().pinTenantModule(UUID.randomUUID(), "   "))
                .isInstanceOf(NetheriteException.class);
    }

    @Test
    void rejectsOversizedSource() {
        properties.setMaxRegoBytes(10);
        assertThatThrownBy(() -> service().pinTenantModule(UUID.randomUUID(), "package a\n\n# longer than ten"))
                .isInstanceOf(NetheriteException.class);
    }

    @Test
    void pushTenantPolicyIsNoOpWhenDisabled() {
        properties.setEnabled(false);
        boolean pushed = service().pushTenantPolicy(UUID.randomUUID(), "package x\n\ndefault decision := \"DENY\"");
        assertThat(pushed).isFalse();
        verify(manageClient, never()).putPolicy(anyString(), anyString());
    }

    @Test
    void pushTenantPolicyFailsClosedWhenCompileRejected() {
        properties.setEnabled(true);
        when(engineClient.isHealthy()).thenReturn(true);
        when(manageClient.compileModule(anyString(), anyString()))
                .thenReturn(new io.netherite.policy.model.OpaPolicyManageResult(false, 400, "rego_parse_error: bad"));

        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> service().pushTenantPolicy(org, "package x\n\ndefault decision := \"DENY\""))
                .isInstanceOf(NetheriteException.class)
                .extracting(e -> ((NetheriteException) e).getErrorCode())
                .isEqualTo(io.netherite.common.exception.SystemErrorCode.ERR_POLICY_PUSH_REJECTED);
        verify(manageClient, never()).putPolicy(anyString(), anyString());
    }

    @Test
    void pushTenantPolicyFailsClosedWhenPutRejected() {
        properties.setEnabled(true);
        when(engineClient.isHealthy()).thenReturn(true);
        when(manageClient.compileModule(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(new io.netherite.policy.model.OpaPolicyManageResult(false, 401, "unauthorized"));

        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> service().pushTenantPolicy(org, "package x\n\ndefault decision := \"DENY\""))
                .isInstanceOf(NetheriteException.class)
                .extracting(e -> ((NetheriteException) e).getErrorCode())
                .isEqualTo(io.netherite.common.exception.SystemErrorCode.ERR_POLICY_PUSH_REJECTED);
    }

    @Test
    void pushTenantPolicyFailsWhenEngineUnreachable() {
        properties.setEnabled(true);
        when(engineClient.isHealthy()).thenReturn(true);
        when(manageClient.compileModule(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(new io.netherite.policy.model.OpaPolicyManageResult(false, 0, "engine unreachable"));

        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> service().pushTenantPolicy(org, "package x\n\ndefault decision := \"DENY\""))
                .isInstanceOf(NetheriteException.class)
                .extracting(e -> ((NetheriteException) e).getErrorCode())
                .isEqualTo(io.netherite.common.exception.SystemErrorCode.ERR_POLICY_PUSH_FAILED);
    }

    @Test
    void pushTenantPolicySucceedsWhenCompileAndPutAccepted() {
        properties.setEnabled(true);
        when(engineClient.isHealthy()).thenReturn(true);
        when(manageClient.compileModule(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));

        boolean pushed = service().pushTenantPolicy(UUID.randomUUID(), "package x\n\ndefault decision := \"DENY\"");
        assertThat(pushed).isTrue();
        verify(manageClient).putPolicy(anyString(), anyString());
    }

    @Test
    void reconcileSkipsPushWhenBaseModuleAlreadyPresent() {
        properties.setEnabled(true);
        when(manageClient.policyExists(properties.getBasePolicyId())).thenReturn(true);

        service().reconcileBasePolicy();

        verify(manageClient, never()).putPolicy(anyString(), anyString());
        assertThat(syncStatus.isBasePolicyPresent()).isTrue();
        assertThat(syncStatus.isReconciled()).isTrue();
    }

    @Test
    void reconcileSkipsPushWhenBaseModuleMountedFromDisk() {
        properties.setEnabled(true);
        when(manageClient.policyExists(properties.getBasePolicyId())).thenReturn(false);
        when(manageClient.policyExists("policies/base.rego")).thenReturn(true);

        service().reconcileBasePolicy();

        verify(manageClient, never()).putPolicy(anyString(), anyString());
        assertThat(syncStatus.isBasePolicyPresent()).isTrue();
        assertThat(syncStatus.isReconciled()).isTrue();
    }

    @Test
    void reconcileInstallsEmbeddedBaseWhenAbsent() {
        properties.setEnabled(true);
        when(manageClient.policyExists(properties.getBasePolicyId())).thenReturn(false);
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));

        service().reconcileBasePolicy();

        verify(manageClient).putPolicy(org.mockito.ArgumentMatchers.eq(properties.getBasePolicyId()), anyString());
        assertThat(syncStatus.isBasePolicyPresent()).isTrue();
    }

    @Test
    void canonicalBaseRegoLoadsFromEmbeddedResource() {
        String base = service().canonicalBaseRego();
        assertThat(base).contains("package netherite.authz")
                .contains("evaluation_output")
                .contains("default decision := \"DENY\"");
    }

    private PolicyVersionEntity activeVersion(UUID orgId) {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(
                PolicyEntity.builder().id(policyId).organizationId(orgId).build()));
        return PolicyVersionEntity.builder()
                .id(UUID.randomUUID())
                .policyId(policyId)
                .versionNumber(1)
                .status("ACTIVE")
                .regoCode("package tenant\n\ndefault decision := \"DENY\"")
                .build();
    }

    @Test
    void reconcilePolicyStateReinstallsMissingActiveModules() {
        properties.setEnabled(true);
        UUID org = UUID.randomUUID();
        PolicyVersionEntity version = activeVersion(org);
        when(policyVersionRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(version));
        when(manageClient.policyExists(properties.getBasePolicyId())).thenReturn(true);
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));

        service().reconcilePolicyState();

        verify(manageClient).putPolicy(eq("netherite-tenant-" + org), anyString());
        assertThat(syncStatus.isReconciled()).isTrue();
        assertThat(syncStatus.getLastOutcome()).contains("activeVersions=1").contains("synced=1");
    }

    @Test
    void reconcilePolicyStateLeavesPresentActiveModulesAlone() {
        properties.setEnabled(true);
        UUID org = UUID.randomUUID();
        PolicyVersionEntity version = activeVersion(org);
        when(policyVersionRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(version));
        when(manageClient.policyExists(properties.getBasePolicyId())).thenReturn(true);
        when(manageClient.policyExists("netherite-tenant-" + org)).thenReturn(true);

        service().reconcilePolicyState();

        verify(manageClient, never()).putPolicy(eq("netherite-tenant-" + org), anyString());
        assertThat(syncStatus.isReconciled()).isTrue();
    }

    @Test
    void reconcilePolicyStateRecordsDegradedWhenEngineRejects() {
        properties.setEnabled(true);
        UUID org = UUID.randomUUID();
        PolicyVersionEntity version = activeVersion(org);
        when(policyVersionRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(version));
        when(manageClient.policyExists(properties.getBasePolicyId())).thenReturn(true);
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(new io.netherite.policy.model.OpaPolicyManageResult(false, 400, "rejected"));

        service().reconcilePolicyState();

        assertThat(syncStatus.isReconciled()).isFalse();
        assertThat(syncStatus.getLastOutcome()).contains("failures=rejected netherite-tenant-");
    }

    @Test
    void reconcilePolicyStateReinstallsVanishedBaseAlongsideActiveModules() {
        properties.setEnabled(true);
        UUID org = UUID.randomUUID();
        PolicyVersionEntity version = activeVersion(org);
        when(policyVersionRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(version));
        when(manageClient.policyExists(anyString())).thenReturn(false);
        when(manageClient.putPolicy(anyString(), anyString()))
                .thenReturn(io.netherite.policy.model.OpaPolicyManageResult.success(200, "ok"));

        service().reconcilePolicyState();

        verify(manageClient).putPolicy(eq(properties.getBasePolicyId()), anyString());
        verify(manageClient).putPolicy(eq("netherite-tenant-" + org), anyString());
        assertThat(syncStatus.isBasePolicyPresent()).isTrue();
        assertThat(syncStatus.isReconciled()).isTrue();
    }
}