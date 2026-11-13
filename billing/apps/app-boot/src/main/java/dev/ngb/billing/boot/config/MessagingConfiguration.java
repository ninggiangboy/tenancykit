package dev.ngb.billing.boot.config;

import dev.ngb.billing.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.core.messaging.broker.BrokerRelay;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
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
    MessagingProperties messagingProperties(
            EventTypeCatalog catalog,
            @Value("${suite.messaging.exchange:billing.events}") String exchange,
            @Value("${suite.replica.queue:billing.platform-events}") String platformQueue,
            @Value("${suite.replica.exchange:platform.events}") String platformExchange,
            @Value("${suite.messaging.relay.batch-size:50}") int batchSize,
            @Value("${suite.messaging.relay.max-attempts:10}") int maxAttempts,
            @Value("${suite.messaging.relay.backoff-base:PT10S}") Duration backoffBase,
            @Value("${suite.messaging.relay.backoff-cap:PT1H}") Duration backoffCap,
            @Value("${suite.messaging.relay.jitter:0.2}") double jitter,
            @Value("${suite.messaging.relay.claim-deadline:PT30S}") Duration claimDeadline,
            @Value("${suite.messaging.document.schema-version:2}") int schemaVersion) {
        Map<String, String> exports = new LinkedHashMap<>();
        catalog.eventTypes().forEach(type -> exports.put(type, exchange));
        List<MessagingProperties.QueueBinding> queues = List.of(new MessagingProperties.QueueBinding(
                platformQueue,
                platformExchange,
                List.of("tenant.changed", "tenant-domain.changed", "tenant-system.changed", "user-access.changed", "entitlement.changed")));
        return new MessagingProperties(exchange, exports, queues,
                new Relay(batchSize, maxAttempts, backoffBase, backoffCap, jitter, claimDeadline),
                new Document(schemaVersion));
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
