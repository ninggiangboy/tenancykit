package dev.ngb.billing.boot.config;

import dev.ngb.billing.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.core.messaging.broker.BrokerRelay;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Billing's part in the suite broker. It exports every event type that has a schema under
 * {@code contracts/events/billing}, so a new schema is published without another wiring change.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /**
     * Billing produces to {@code billing.events} and consumes Platform's five state-transfer events from
     * {@code platform.events} through {@code billing.platform-events}. The queue is declared here so every deployment
     * gets it, and the replica's listener container is what actually reads it.
     */
    @Bean
    MessagingProperties messagingProperties(EventTypeCatalog catalog, SuiteProperties suite) {
        SuiteProperties.Messaging messaging = suite.messaging();
        SuiteProperties.Messaging.Relay relay = messaging.relay();
        Map<String, String> exports = new LinkedHashMap<>();
        catalog.eventTypes().forEach(type -> exports.put(type, messaging.exchange()));
        List<MessagingProperties.QueueBinding> queues = List.of(new MessagingProperties.QueueBinding(
                suite.replica().queue(), suite.replica().exchange(), ReplicaStream.eventTypes()));
        return new MessagingProperties(messaging.exchange(), exports, queues,
                new Relay(relay.batchSize(), relay.maxAttempts(), relay.backoffBase(), relay.backoffCap(), relay.jitter(), relay.claimDeadline()),
                new Document(messaging.document().schemaVersion()));
    }

    @Bean
    Declarables suiteTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }

    @Bean
    BrokerRelay brokerRelay(
            UnitOfWork unitOfWork, DSLContext dsl, JsonMapper json, RabbitTemplate rabbit, Clock clock, MeterRegistry meters,
            MessagingProperties properties) {
        return new BrokerRelay(unitOfWork, dsl, json, rabbit, clock, meters, properties, "billing");
    }
}
