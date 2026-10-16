package io.netherite.policy.monitoring;

import io.netherite.policy.spi.OpaPolicyEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpaHealthIndicatorTest {

    @Test
    void reportsUpWhenOpaReachable() {
        OpaPolicyEngineClient client = mock(OpaPolicyEngineClient.class);
        when(client.isHealthy()).thenReturn(true);

        Health health = new OpaHealthIndicator(client).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("dependency", "opa");
    }

    @Test
    void reportsDownWithFailClosedDetailWhenOpaUnreachable() {
        OpaPolicyEngineClient client = mock(OpaPolicyEngineClient.class);
        when(client.isHealthy()).thenReturn(false);

        Health health = new OpaHealthIndicator(client).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("dependency", "opa")
                .containsEntry("failClosed", true);
    }
}