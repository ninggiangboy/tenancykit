package dev.ngb.loyalty.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * One RabbitMQ container shared by every integration test in the JVM; the image matches docker-compose.yml.
 *
 * <p>The admin user is used directly, which is what the topology declaration needs: the app declares its queue, its
 * bindings and the suite's dead-letter pair on startup, and a permission-restricted user would be refused.
 */
public final class RabbitContainerConfig {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static {
        RABBIT.start();
    }

    private RabbitContainerConfig() {}

    public static RabbitMQContainer rabbit() {
        return RABBIT;
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
