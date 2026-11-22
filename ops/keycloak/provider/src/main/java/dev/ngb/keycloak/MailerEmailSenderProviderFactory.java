package dev.ngb.keycloak;

import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import org.keycloak.Config;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailSenderProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers the mailer sender as the server's email sender.
 *
 * <p>It outranks the stock SMTP sender, so no realm needs SMTP settings. The broker is configured on the server, as
 * {@code KC_SPI_EMAIL_SENDER__SUITE_MAILER__<NAME>} variables: {@code HOST}, {@code PORT}, {@code USERNAME},
 * {@code PASSWORD}, {@code VHOST}, {@code TLS} (true for Amazon MQ's AMQPS), {@code EXCHANGE}, {@code ROUTING_KEY}
 * and {@code FROM_KEY}. A server that has no
 * {@code HOST} still starts and signs people in; only sending a mail fails, with a message that says why.
 */
public class MailerEmailSenderProviderFactory implements EmailSenderProviderFactory {

    public static final String PROVIDER_ID = "suite-mailer";

    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000;

    private MailerSink sink;
    private String fromKey;

    @Override
    public EmailSenderProvider create(KeycloakSession session) {
        return new MailerEmailSenderProvider(sink, fromKey);
    }

    @Override
    public void init(Config.Scope config) {
        fromKey = config.get("fromKey", "noreply");
        String host = config.get("host");
        if (host == null || host.isBlank()) {
            sink = command -> {
                throw new IOException("the suite mailer sender has no broker host; set "
                        + "KC_SPI_EMAIL_SENDER__SUITE_MAILER__HOST");
            };
            return;
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(config.getInt("port", 5672));
        factory.setUsername(config.get("username"));
        factory.setPassword(config.get("password"));
        factory.setVirtualHost(config.get("vhost", "/"));
        if (config.getBoolean("tls", false)) {
            try {
                factory.useSslProtocol();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("the broker connection could not use TLS", e);
            }
        }
        factory.setConnectionTimeout(5_000);
        factory.setHandshakeTimeout(5_000);
        sink = new MailerPublisher(
                factory,
                config.get("exchange", "mailer.commands"),
                config.get("routingKey", MailerCommand.TYPE),
                CONFIRM_TIMEOUT_MILLIS);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (sink instanceof MailerPublisher publisher) {
            publisher.close();
        }
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /** Higher than the stock sender's 0, which is how the server chooses between two senders. */
    @Override
    public int order() {
        return 100;
    }
}
