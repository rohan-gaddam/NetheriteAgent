package io.netherite.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * In migrate-only mode no HTTP service is required: the process applies Flyway
 * migrations and exits. Port {@code -1} disables the embedded web server so the
 * migration job never opens a port (avoids conflicts with replicas during
 * containerized rollouts).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(name = "netherite.migrate-only", havingValue = "true")
public class MigrateOnlyWebServerConfig {

    @Bean
    public WebServerFactoryCustomizer<ConfigurableWebServerFactory> migrateOnlyDisableWebServer() {
        return factory -> factory.setPort(-1);
    }
}