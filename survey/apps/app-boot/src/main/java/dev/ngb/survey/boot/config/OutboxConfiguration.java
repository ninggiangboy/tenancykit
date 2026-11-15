package dev.ngb.survey.boot.config;

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
 * <p>The outbox rows are written by the sending use cases inside their own transaction; only the worker's relay ever
 * calls the broker, so an api replica cannot publish commands.
 */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    @Bean
    EventPublisher eventPublisher(DSLContext dsl, JsonMapper json) {
        return new JooqOutboxAppender(dsl, json);
    }

    /**
     * A zero-route relay is a silent no-op: {@code relayDue()} returns an empty result when {@code exports} names no
     * type, so a misnamed property would enqueue commands that never leave. This bean makes that a startup failure.
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
        return new BrokerRelay(unitOfWork, dsl, json, rabbit, clock, meters, properties, "survey");
    }

    /** The route set the relay resolves; its constructor is the non-empty assertion. */
    record OutboxRoutes(MessagingProperties properties) {

        OutboxRoutes {
            if (properties.exports().isEmpty()) {
                throw new IllegalStateException(
                        "survey.messaging.exports is empty: the broker relay would never publish anything");
            }
        }
    }
}
