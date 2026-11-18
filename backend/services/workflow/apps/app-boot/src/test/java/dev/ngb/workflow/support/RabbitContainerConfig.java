package dev.ngb.workflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * One RabbitMQ container shared by every integration test in the JVM; the image matches docker-compose.yml.
 *
 * <p>The admin user is used directly, which is what the topology declaration needs: the app declares its exchange,
 * its queue and the dead-letter pair on startup, and a permission-restricted user would be refused.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitContainerConfig {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static {
        RABBIT.start();
    }

    public static RabbitMQContainer rabbit() {
        return RABBIT;
    }

    @Bean
    DynamicPropertyRegistrar rabbitProperties() {
        return registry -> {
            registry.add("spring.rabbitmq.host", RABBIT::getHost);
            registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
            registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        };
    }
}
