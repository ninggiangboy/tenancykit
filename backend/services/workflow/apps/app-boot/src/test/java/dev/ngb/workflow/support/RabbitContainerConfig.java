package dev.ngb.workflow.support;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * One RabbitMQ container shared by every integration test in the JVM; the image matches docker-compose.yml.
 *
 * <p>The admin user is used directly, which is what the topology declaration needs: the app declares its exchange,
 * its queue and the dead-letter pair on startup, and a permission-restricted user would be refused.
 *
 * <p>{@code platform.events} is declared here because Platform owns it, and no Platform runs beside this container.
 * Workflow binds its replica queue to that exchange at startup, and a binding to an exchange that does not exist is
 * refused by the broker with 404 NOT_FOUND, so the harness plays Platform's part.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitContainerConfig {

    /** Platform's exchange, declared by Platform in a real deployment. */
    private static final String PLATFORM_EXCHANGE = "platform.events";

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static {
        RABBIT.start();
        declarePlatformExchange();
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

    private static void declarePlatformExchange() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(PLATFORM_EXCHANGE, "topic", true);
        } catch (Exception e) {
            throw new IllegalStateException("could not declare " + PLATFORM_EXCHANGE + " on the test broker", e);
        }
    }
}
