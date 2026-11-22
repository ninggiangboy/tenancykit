package dev.ngb.loyalty.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.loyalty.Application;
import dev.ngb.loyalty.support.PostgresContainerConfig;
import dev.ngb.loyalty.support.RabbitContainerConfig;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The migration runtime starts on its own, serves nothing and writes the schema as the table owner, then exits. It
 * creates neither a listener nor a job, so a deployment cannot record a delivery before the schema exists.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("migrate")
class MigrateRuntimeStartupTest {

    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        PostgresContainerConfig.register(registry);
        RabbitContainerConfig.register(registry);
        registry.add("loyalty.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void theMigrateRuntimeStartsAndWritesTheSchema() throws SQLException {
        assertThat(context.getBean(Application.class)).isNotNull();
        assertThat(context.getBeanNamesForType(dev.ngb.loyalty.boot.jobs.OutboxRelayJob.class))
                .as("the migrate runtime schedules nothing").isEmpty();
        assertThat(context.getBeanNamesForType(dev.ngb.loyalty.boot.jobs.WebhookDeliveryJob.class))
                .as("the migrate runtime delivers nothing").isEmpty();
        assertThat(context.getBeanNamesForType(
                        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class))
                .as("the migrate runtime consumes nothing").isEmpty();
        try (Connection owner = PostgresContainerConfig.asOwner();
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery(
                        "select count(*) from flyway_schema_history where success")) {
            history.next();
            assertThat(history.getInt(1)).as("the migrate runtime ran the migrations").isGreaterThanOrEqualTo(1);
        }
    }
}
