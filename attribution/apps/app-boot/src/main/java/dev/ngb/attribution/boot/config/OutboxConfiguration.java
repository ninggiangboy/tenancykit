package dev.ngb.attribution.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.messaging.broker.BrokerRelay;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox and its broker relay.
 *
 * <p>The baseline copies the outbox and its relay from the suite skeleton, but no publisher exists yet: v1 declares no
 * domain event. The relay is still wired and its route set is asserted non-empty, so the moment a later phase adds an
 * export it cannot silently publish nothing.
 */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    @Bean
    EventPublisher eventPublisher(DSLContext dsl, JsonMapper json) {
        return new JooqOutboxAppender(dsl, json);
    }

    /**
     * A zero-route relay is a silent no-op: {@code relayDue()} returns an empty result when {@code exports} names no
     * type, so a misnamed property would enqueue events that never leave. This bean makes that a startup failure.
     */
    @Bean
    OutboxRoutes outboxRoutes(MessagingProperties properties) {
        return new OutboxRoutes(properties);
    }

    @Bean
    BrokerRelay brokerRelay(
            UnitOfWork unitOfWork,
            DSLContext dsl,
            JsonMapper json,
            RabbitTemplate rabbit,
            Clock clock,
            MeterRegistry meters,
            MessagingProperties properties) {
        return new BrokerRelay(unitOfWork, dsl, json, rabbit, clock, meters, properties, "attribution");
    }

    /** The route set the relay resolves; its constructor is the non-empty assertion. */
    record OutboxRoutes(MessagingProperties properties) {

        OutboxRoutes {
            if (properties.exports().isEmpty()) {
                throw new IllegalStateException(
                        "attribution.messaging.exports is empty: the broker relay would never publish anything");
            }
        }
    }
}
