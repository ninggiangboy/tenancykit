package dev.ngb.loyalty.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Backoff;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox and the in-process relay that empties it.
 *
 * <p>A use case writes its event to the outbox inside its own transaction, so a change that rolls back sends nothing.
 * The relay publishes each due row as a Spring event in the relay's own transaction, and the copied webhook context
 * listens to the same events in that transaction to queue one delivery per subscribed endpoint. There is no
 * {@code BrokerRelay}: loyalty publishes to no exchange, so an outbox row never reaches the broker.
 */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    @Bean
    EventPublisher eventPublisher(DSLContext dsl, JsonMapper json) {
        return new JooqOutboxAppender(dsl, json);
    }

    @Bean
    OutboxRelay outboxRelay(
            UnitOfWork unitOfWork,
            DSLContext dsl,
            JsonMapper json,
            ApplicationEventPublisher publisher,
            Clock clock,
            MeterRegistry meters,
            LoyaltySettings settings) {
        LoyaltySettings.Relay relay = settings.relay();
        return new OutboxRelay(
                unitOfWork,
                dsl,
                json,
                publisher,
                clock,
                meters,
                "loyalty",
                relay.batchSize(),
                relay.maxAttempts(),
                new Backoff(relay.backoffBase(), relay.backoffCap(), relay.jitter()));
    }
}
