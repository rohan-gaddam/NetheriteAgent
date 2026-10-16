package io.netherite.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenAPI 3.1 documentation configuration.
 * Only active in non-production profiles to prevent API surface exposure in production.
 */
@Configuration
@Profile({"!production"})
public class OpenApiConfig {

    @Bean
    public OpenAPI netheriteOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NetheriteAgent Control Plane API")
                        .description("Enterprise Control Plane for Security, Authorization, and Enforcement of Autonomous AI Agents")
                        .version("v0.1.0")
                        .contact(new Contact().name("Netherite Security Team").email("security@netherite.io"))
                        .license(new License().name("Apache 2.0").url("https://netherite.io/license")));
    }
}
