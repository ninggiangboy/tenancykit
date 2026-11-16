package dev.ngb.crm.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.crm.Application;
import dev.ngb.crm.boot.config.OutboxConfiguration;
import dev.ngb.crm.boot.jobs.OutboxRelayJob;
import dev.ngb.crm.support.PostgresContainerConfig;
import dev.ngb.crm.support.RabbitContainerConfig;
import dev.ngb.crm.webhook.adapter.delivery.messaging.WebhookEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The api runtime starts on its own: HTTP, the actuator and no job and no replica rebuild. */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
class ApiRuntimeStartupTest {

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
        registry.add("crm.jobs.enabled", () -> "false");
        registry.add("crm.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void theApiRuntimeStarts() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }

    /**
     * The startup assertion: the outbox has one consumer, and the api runtime must know it too, because a relay round
     * with no listener would mark every row delivered and queue nothing. No job runs here, so the relay bean itself
     * stays out of this runtime.
     */
    @Test
    void theWebhookListenerIsRegisteredAsTheOutboxConsumer() {
        assertThat(context.getBean(WebhookEventListener.class)).isNotNull();
        assertThat(context.getBean(OutboxConfiguration.OutboxConsumers.class).webhookListener())
                .isSameAs(context.getBean(WebhookEventListener.class));
        assertThat(context.getBeanNamesForType(OutboxRelayJob.class)).isEmpty();
    }
}
