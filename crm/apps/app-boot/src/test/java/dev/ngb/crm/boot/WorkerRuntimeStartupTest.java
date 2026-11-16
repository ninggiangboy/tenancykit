package dev.ngb.crm.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.crm.Application;
import dev.ngb.crm.boot.jobs.HousekeepingJob;
import dev.ngb.crm.boot.jobs.OutboxRelayJob;
import dev.ngb.crm.boot.jobs.WebhookDeliveryJob;
import dev.ngb.crm.support.PostgresContainerConfig;
import dev.ngb.crm.support.RabbitContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The worker runtime starts on its own: the replica listener, the in-process outbox relay and the three jobs. The
 * rebuild is switched off, so the context starts without a Platform to page.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("worker")
class WorkerRuntimeStartupTest {

    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("crm.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void theWorkerRuntimeStarts() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }

    @Test
    void theWorkerOwnsEveryJob() {
        assertThat(context.getBeanNamesForType(OutboxRelayJob.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(WebhookDeliveryJob.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(HousekeepingJob.class)).isNotEmpty();
    }

    /** Readiness stays DOWN until one replica rebuild has finished, which is what the api runtime waits for. */
    @Test
    void readinessIsDownBeforeTheFirstRebuild() {
        assertThat(context.getBean(dev.ngb.core.replica.ReplicaState.class).rebuilt()).isFalse();
    }
}
