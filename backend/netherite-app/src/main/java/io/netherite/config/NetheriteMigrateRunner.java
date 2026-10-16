package io.netherite.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Migrate-only mode for containerized deployments.
 *
 * <p>Set {@code netherite.migrate-only=true} (e.g. {@code --netherite.migrate-only=true}
 * or {@code NETHERITE_MIGRATE_ONLY=true}) on the standard application image to apply
 * Flyway migrations and exit. This is the single migration process for multi-replica
 * deployments: the migration service runs once ahead of the replicas, and the replicas
 * boot with {@code FLYWAY_ENABLED=false} so only one process ever migrates the schema.
 *
 * <p>Exit codes: 0 on successful migration to the latest schema; the JVM exits non-zero
 * if migration fails (Flyway throws), aborting the deployment.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "netherite.migrate-only", havingValue = "true")
public class NetheriteMigrateRunner implements ApplicationRunner {

    private final Flyway flyway;

    public NetheriteMigrateRunner(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migrate-only mode: applying Flyway migrations to the schema...");
        MigrateResult result = flyway.migrate();
        log.info("Flyway migration complete: initialVersion={}, targetVersion={}, " +
                        "migrationsExecuted={}, success={}",
                result.initialSchemaVersion,
                result.targetSchemaVersion,
                result.migrationsExecuted,
                result.success);
        if (result.success) {
            System.exit(0);
        }
        throw new IllegalStateException("Flyway migrate() reported success=false; refusing to start.");
    }
}