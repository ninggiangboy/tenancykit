package dev.ngb.attribution.boot.config;

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
 * Attribution's part in the suite broker.
 *
 * <p>It consumes two streams — the five Platform state-transfer events on {@code attribution.platform-events}, and its
 * own {@code attribution.ingest} queue — and declares no {@code *.events} exchange, because in v1 it exports no domain
 * event another app consumes. The ingest exchange is its own: the redirect buffers a click and a publisher sends
 * batches there, the worker consumes them.
 *
 * <p>One ingest routing key, {@code click}, is what the queue binds. Later phases add {@code page-view},
 * {@code visitor-bound} and {@code goal} to the same binding list, so a new event type is one entry and not a new
 * queue. The envelope {@code schemaVersion} is <b>2</b>; a wrong value rejects every message.
 *
 * <p>{@code MessagingProperties.exchange} is the <em>producing</em> exchange, so it is {@code attribution.ingest}.
 * {@code SuiteTopology} declares every exchange a queue binds to as well as the producing one.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /**
     * Two consumed queues, their dead-letter pairs, and the one produced exchange. The export entry is what
     * {@code BrokerRelay} resolves the exchange from, so {@code OutboxConfiguration} asserts the route set is non-empty
     * at startup.
     */
    @Bean
    MessagingProperties messagingProperties(AttributionSettings settings) {
        AttributionSettings.Relay relay = settings.relay();
        return new MessagingProperties(
                settings.ingest().exchange(),
                Map.of("click", settings.ingest().exchange()),
                List.of(
                        new QueueBinding(
                                settings.messaging().queue(),
                                settings.messaging().exchange(),
                                ReplicaStream.eventTypes()),
                        new QueueBinding(settings.ingest().queue(), settings.ingest().exchange(), List.of("click"))),
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
    Declarables attributionTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }
}
