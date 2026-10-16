package io.netherite.security.rate;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Dedicated, fail-fast Redisson client for the rate-limiting subsystem.
 *
 * <p>Unlike the shared lock client (3s timeout, retries), this client is intentionally tuned for
 * the synchronous pre-auth and identity tiers: short timeouts and no retries so a sick Redis is
 * detected in hundreds of milliseconds, not seconds. {@link RateLimitService} additionally wraps
 * this client in a local circuit breaker, so once the broker goes down the limiter stops paying
 * the Redis round-trip entirely and answers from the fail-closed/fail-open policy.</p>
 */
@Configuration
public class RateLimitRedissonConfig {

    @Bean("redissonRateLimitClient")
    @Lazy
    @ConditionalOnProperty(prefix = "netherite.distributed", name = "provider",
            havingValue = "redis", matchIfMissing = true)
    public RedissonClient redissonRateLimitClient(
            RateLimitProperties properties,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword) {

        RateLimitProperties.Dependency dependency = properties.getDependency();

        Config config = new Config();
        config.setCodec(new StringCodec());

        SingleServerConfig server = config.useSingleServer();
        server.setAddress("redis://" + redisHost + ":" + redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            server.setPassword(redisPassword);
        }
        server.setConnectTimeout(dependency.getConnectTimeoutMillis());
        server.setTimeout(dependency.getTimeoutMillis());
        server.setRetryAttempts(dependency.getRetryAttempts());
        server.setRetryInterval(50);
        server.setConnectionPoolSize(4);
        server.setConnectionMinimumIdleSize(1);
        server.setPingConnectionInterval(30000);
        server.setIdleConnectionTimeout(30000);
        server.setDnsMonitoringInterval(5000);

        return Redisson.create(config);
    }
}