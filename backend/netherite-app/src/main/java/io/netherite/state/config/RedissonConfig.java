package io.netherite.state.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redisson client wiring for the distributed lock provider.
 *
 * <p>The Redis address is sourced from the existing {@code spring.data.redis.*} block so
 * operators keep a single place to configure the endpoint. The bean is created lazily:
 * {@code Redisson.create(config)} connects eagerly, so without {@code @Lazy} an idle Redis
 * would abort application startup. With {@code @Lazy} the client is created on first use,
 * and {@code RedisDistributedLockManager} wraps that (and every later) call in a fail-closed
 * {@code NetheriteException(ERR_SYSTEM_UNAVAILABLE)} - the control plane never silently
 * falls back to a non-distributed mode.
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    @ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
            havingValue = "redis", matchIfMissing = true)
    public RedissonClient redissonClient(
            NetheriteDistributedProperties properties,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword) {

        Config config = new Config();
        config.setCodec(new StringCodec());

        SingleServerConfig server = config.useSingleServer();
        server.setAddress("redis://" + redisHost + ":" + redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            server.setPassword(redisPassword);
        }
        server.setConnectTimeout(properties.getRedis().getConnectTimeoutMillis());
        server.setTimeout(properties.getRedis().getTimeoutMillis());
        server.setConnectionPoolSize(properties.getRedis().getConnectionPoolSize());
        server.setConnectionMinimumIdleSize(properties.getRedis().getConnectionMinimumIdleSize());
        server.setRetryAttempts(2);
        server.setRetryInterval(500);
        server.setPingConnectionInterval(30000);
        server.setIdleConnectionTimeout(30000);
        server.setDnsMonitoringInterval(5000);

        return Redisson.create(config);
    }
}