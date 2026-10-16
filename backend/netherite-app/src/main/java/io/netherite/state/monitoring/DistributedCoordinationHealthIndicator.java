package io.netherite.state.monitoring;

import io.netherite.state.service.NodeIdentity;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Reports reachability of the Redis coordination dependency (locks) to the actuator health
 * endpoint. The control plane boots and serves even when Redis is down — every coordination
 * call fails closed to 503 — and this indicator lets operators watch recovery.
 */
@Component
@ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
        havingValue = "redis", matchIfMissing = true)
public class DistributedCoordinationHealthIndicator implements HealthIndicator {

    private final RedissonClient redisson;
    private final NodeIdentity nodeIdentity;

    public DistributedCoordinationHealthIndicator(
            @Lazy @Qualifier("redissonClient") RedissonClient redisson,
            NodeIdentity nodeIdentity) {
        this.redisson = redisson;
        this.nodeIdentity = nodeIdentity;
    }

    @Override
    public Health health() {
        try {
            redisson.getKeys().count();
            return Health.up()
                    .withDetail("dependency", "redis")
                    .withDetail("node", nodeIdentity.getId())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("dependency", "redis")
                    .withDetail("node", nodeIdentity.getId())
                    .withException(e)
                    .build();
        }
    }
}