package dev.ngb.platform.boot.config;

import dev.ngb.core.messaging.broker.BrokerRelay;
import dev.ngb.core.messaging.config.MessagingProperties;
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

/** Platform's part in the suite broker: the state-transfer events every consumer replicates. */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /** Every schema under contracts/events/platform; a new schema is a deliberate addition to this list. */
    private static final List<String> EXPORTED_TYPES =
            List.of("tenant.changed", "tenant-domain.changed", "tenant-system.changed", "user-access.changed", "entitlement.changed");

    @Bean
    MessagingProperties messagingProperties(
            @Value("${suite.messaging.exchange:platform.events}") String exchange,
            @Value("${suite.messaging.relay.batch-size:50}") int batchSize,
            @Value("${suite.messaging.relay.max-attempts:10}") int maxAttempts,
            @Value("${suite.messaging.relay.backoff-base:PT10S}") Duration backoffBase,
            @Value("${suite.messaging.relay.backoff-cap:PT1H}") Duration backoffCap) {
        Map<String, String> exports = new LinkedHashMap<>();
        EXPORTED_TYPES.forEach(type -> exports.put(type, exchange));
        return new MessagingProperties(exchange, exports, List.of(), new Relay(batchSize, maxAttempts, backoffBase, backoffCap));
    }

    @Bean
    Declarables suiteTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }

    @Bean
    BrokerRelay brokerRelay(
            UnitOfWork unitOfWork, DSLContext dsl, JsonMapper json, RabbitTemplate rabbit, Clock clock, MeterRegistry meters,
            MessagingProperties properties) {
        return new BrokerRelay(unitOfWork, dsl, json, rabbit, clock, meters, properties, "platform");
    }
}
