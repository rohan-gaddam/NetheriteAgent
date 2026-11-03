package io.netherite.connectors.erpnext;

import io.netherite.approval.service.ApprovalEventPublisher;
import io.netherite.approval.spi.ApprovalService;
import io.netherite.audit.spi.AuditService;
import io.netherite.audit.spi.DataRedactionService;
import io.netherite.common.model.ConnectorType;
import io.netherite.connectors.http.HttpRestResourceConnector;
import io.netherite.connectors.service.ConnectorExecutionService;
import io.netherite.connectors.spi.ResourceConnector;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.dashboard.service.HealthCheckService;
import io.netherite.policy.service.PolicyEvaluationService;
import io.netherite.routing.spi.ResourceRoutingService;
import io.netherite.state.config.NetheriteDistributedProperties;
import io.netherite.state.spi.DistributedLockManager;
import io.netherite.state.spi.IdempotencyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class ErpNextConnectorSelectionTest {

    private ConnectorExecutionService serviceWith(ResourceConnector... connectors) {
        return new ConnectorExecutionService(
                mock(PolicyEvaluationService.class),
                mock(ResourceRoutingService.class),
                mock(SsrfGuardValidator.class),
                List.of(connectors),
                mock(IdempotencyStore.class),
                mock(DistributedLockManager.class),
                mock(ApprovalService.class),
                mock(ApprovalEventPublisher.class),
                mock(AuditService.class),
                mock(DataRedactionService.class),
                mock(HealthCheckService.class),
                mock(NetheriteDistributedProperties.class));
    }

    @Test
    @DisplayName("ERPNEXT and HTTP_REST routes select their dedicated connectors without ambiguity")
    void selectsConnectorByType() {
        SsrfGuardValidator ssrf = mock(SsrfGuardValidator.class);
        ErpNextResourceConnector erpNext = new ErpNextResourceConnector(ssrf);
        HttpRestResourceConnector httpRest = new HttpRestResourceConnector(ssrf);
        ConnectorExecutionService service = serviceWith(erpNext, httpRest);

        ResourceConnector erpNextSelection =
                ReflectionTestUtils.invokeMethod(service, "selectConnector", ConnectorType.ERPNEXT);
        ResourceConnector httpSelection =
                ReflectionTestUtils.invokeMethod(service, "selectConnector", ConnectorType.HTTP_REST);

        assertInstanceOf(ErpNextResourceConnector.class, erpNextSelection);
        assertInstanceOf(HttpRestResourceConnector.class, httpSelection);
    }

    @Test
    @DisplayName("Selection is independent of connector bean ordering")
    void selectionIsOrderIndependent() {
        SsrfGuardValidator ssrf = mock(SsrfGuardValidator.class);
        ErpNextResourceConnector erpNext = new ErpNextResourceConnector(ssrf);
        HttpRestResourceConnector httpRest = new HttpRestResourceConnector(ssrf);
        ConnectorExecutionService service = serviceWith(httpRest, erpNext);

        assertInstanceOf(ErpNextResourceConnector.class,
                ReflectionTestUtils.invokeMethod(service, "selectConnector", ConnectorType.ERPNEXT));
        assertInstanceOf(HttpRestResourceConnector.class,
                ReflectionTestUtils.invokeMethod(service, "selectConnector", ConnectorType.HTTP_REST));
    }

    @Test
    @DisplayName("Unregistered connector types have no selection")
    void unregisteredTypeHasNoSelection() {
        ConnectorExecutionService service = serviceWith(new ErpNextResourceConnector(mock(SsrfGuardValidator.class)));

        assertNull(ReflectionTestUtils.invokeMethod(service, "selectConnector", ConnectorType.GRAPHQL));
    }
}
