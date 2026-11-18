package dev.ngb.attribution.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.Application;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.support.RabbitContainerConfig;
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
        registry.add("attribution.jobs.enabled", () -> "false");
        // Tracking has no shipped default: a blank key fails startup, so the test supplies its own.
        registry.add("attribution.keys.visitor-hash-key", () -> "test-visitor-hash-secret");
        registry.add("attribution.keys.click-keys.k1", () -> "test-click-secret");
    }

    @Test
    void theApiRuntimeStarts() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }
}
