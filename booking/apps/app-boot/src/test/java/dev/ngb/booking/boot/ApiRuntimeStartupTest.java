package dev.ngb.booking.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.Application;
import dev.ngb.booking.support.PostgresContainerConfig;
import dev.ngb.booking.support.RabbitContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The api runtime starts on its own: HTTP and the actuator, no job, no replica rebuild. */
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
        registry.add("booking.jobs.enabled", () -> "false");
        // The webhook secret cipher refuses to start without a 32-byte key.
        registry.add("booking.webhooks.master-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("booking.gate.keys.k1", () -> "test-customer-token-secret");
        // A link key has no shipped default: a blank key fails startup, so the test supplies its own.
        // The webhook secret cipher refuses to start without a 32-byte key.
    }

    @Test
    void theApiRuntimeStarts() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }
}
