package dev.ngb.notification.boot.config;

import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.notification.boot.config.NotificationSettings.Email;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Notification's part in the suite broker.
 *
 * <p>It consumes the five Platform state-transfer streams on its own queue and produces one command:
 * {@code email.send} to {@code mailer.commands}. It declares no exchange of its own, because it exports no event that
 * another app consumes. The envelope {@code schemaVersion} is <b>2</b>; a wrong value rejects every message.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /**
     * One queue, one dead-letter pair, and the one export that matters. The export entry is what
     * {@code BrokerRelay} resolves the exchange from; a misnamed property would make the relay a silent no-op, so
     * {@code OutboxConfiguration} asserts the route set is non-empty at startup.
     */
    @Bean
    MessagingProperties messagingProperties(NotificationSettings settings) {
        Email email = settings.email();
        return new MessagingProperties(
                settings.messaging().exchange(),
                Map.of(settings.commands().routingKey(), settings.commands().exchange()),
                List.of(new QueueBinding(
                        settings.messaging().queue(),
                        settings.messaging().exchange(),
                        ReplicaStream.eventTypes())),
                new Relay(
                        email.batchSize(),
                        email.maxAttempts(),
                        email.backoffBase(),
                        email.backoffCap(),
                        email.jitter(),
                        email.claimDeadline()),
                new Document(2));
    }

    @Bean
    Declarables notificationTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }
}
