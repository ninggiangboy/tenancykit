package dev.ngb.mailroom.boot.config;

import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Email's part in the suite broker.
 *
 * <p>It consumes two streams — the five Platform state-transfer events on {@code mailroom.platform-events}, and mailer's
 * {@code email-delivery.changed} on {@code mailroom.mailer-events} — and produces one command: {@code email.send} into
 * {@code mailer.commands}. It declares no exchange of its own, because it exports no event that another app consumes.
 * The envelope {@code schemaVersion} is <b>2</b>; a wrong value rejects every message.
 *
 * <p>{@code MessagingProperties.exchange} is the <em>producing</em> exchange, so it is {@code mailer.commands}. The two
 * consumed exchanges belong to Platform and mailer: the queues bind to them as foreign exchanges, which the broker user
 * may do but not declare, so {@code SuiteTopology} declares only the producing exchange and the dead-letter exchange.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /**
     * Two queues, two dead-letter pairs, and the one export that matters. The export entry is what {@code BrokerRelay}
     * resolves the exchange from; a misnamed property would make the relay a silent no-op, so {@code OutboxConfiguration}
     * asserts the route set is non-empty at startup.
     */
    @Bean
    MessagingProperties messagingProperties(MailroomSettings settings) {
        MailroomSettings.Relay relay = settings.relay();
        return new MessagingProperties(
                settings.commands().exchange(),
                Map.of(settings.commands().routingKey(), settings.commands().exchange()),
                List.of(
                        QueueBinding.toForeignExchange(
                                settings.messaging().queue(),
                                settings.messaging().exchange(),
                                ReplicaStream.eventTypes()),
                        QueueBinding.toForeignExchange(
                                settings.mailer().queue(),
                                settings.mailer().exchange(),
                                List.of("email-delivery.changed"))),
                new Relay(
                        relay.batchSize(),
                        relay.maxAttempts(),
                        relay.backoffBase(),
                        relay.backoffCap(),
                        relay.jitter(),
                        relay.claimDeadline()),
                new Document(2));
    }

    @Bean
    Declarables mailroomTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }
}
