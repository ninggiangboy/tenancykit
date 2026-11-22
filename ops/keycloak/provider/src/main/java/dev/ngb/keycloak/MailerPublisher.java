package dev.ngb.keycloak;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes a command to the mailer's exchange over one shared connection.
 *
 * <p>The broker user this connects as may write {@code mailer.commands} and nothing else, so the publisher declares
 * nothing: it relies on the exchange and its binding existing, and publishes with the mandatory flag so a command no
 * queue accepted is an error and not a silent drop. The publish is confirmed by the broker before it returns.
 */
final class MailerPublisher implements MailerSink, AutoCloseable {

    private final ConnectionFactory factory;
    private final String exchange;
    private final String routingKey;
    private final long confirmTimeoutMillis;
    private Connection connection;

    MailerPublisher(ConnectionFactory factory, String exchange, String routingKey, long confirmTimeoutMillis) {
        this.factory = factory;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /** @throws IOException when the broker is unreachable, refuses the message, or has no queue for it */
    @Override
    public void publish(MailerCommand command) throws IOException {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .contentEncoding("UTF-8")
                .messageId(command.eventId().toString())
                .type(MailerCommand.TYPE)
                .headers(Map.of("tenant-id", command.tenantId().toString()))
                .deliveryMode(2)
                .build();
        AtomicBoolean unroutable = new AtomicBoolean();
        try (Channel channel = connection().createChannel()) {
            channel.confirmSelect();
            channel.addReturnListener(returned -> unroutable.set(true));
            channel.basicPublish(exchange, routingKey, true, properties, command.body());
            channel.waitForConfirmsOrDie(confirmTimeoutMillis);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("the broker did not confirm the command", e);
        }
        if (unroutable.get()) {
            throw new IOException("no queue is bound to " + exchange + " for " + routingKey);
        }
    }

    private synchronized Connection connection() throws IOException {
        if (connection == null || !connection.isOpen()) {
            try {
                connection = factory.newConnection("suite-keycloak-mailer");
            } catch (TimeoutException e) {
                throw new IOException("the broker did not answer", e);
            }
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // The server is stopping; there is nothing left to tell.
            }
            connection = null;
        }
    }
}
