package io.netherite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NetheriteAgent — Enterprise AI Agent Control Plane.
 * Modular Monolith bootstrap application.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class NetheriteApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetheriteApplication.class, args);
    }
}
