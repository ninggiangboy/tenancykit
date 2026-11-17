package dev.ngb.workflow.boot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The migrate runtime: Flyway has already run by the time this starts, so the only thing left is to leave with the
 * outcome. Exiting here keeps a deployment from carrying a second, idle process after the schema is ready.
 */
@Profile("migrate")
@Component
@ConditionalOnProperty(name = "workflow.migrate.exit-on-finish", havingValue = "true", matchIfMissing = true)
public class MigrateRuntime implements ApplicationRunner {

    private final ApplicationContext context;

    public MigrateRuntime(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
