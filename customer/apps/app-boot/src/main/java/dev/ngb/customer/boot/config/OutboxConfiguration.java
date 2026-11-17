package dev.ngb.customer.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Backoff;
import dev.ngb.customer.webhook.adapter.delivery.messaging.WebhookEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox and its in-process relay.
 *
 * <p>customer produces no broker message: the outbox rows the business contexts write inside their own transaction are
 * turned into Spring {@code EventEnvelope}s here, and the webhook listener records one delivery per subscribed
 * endpoint inside the same transaction. A relay that is missing its consumer is the silent failure this class exists
 * to prevent — {@code relayDue()} would find no listener, mark every row {@code DELIVERED} and no webhook would ever
 * leave — so {@link OutboxConsumers} asserts the listener bean is present at startup.
 *
 * <p>{@code BrokerRelay} is deliberately absent: it would resolve an export route from an exchange customer does not own.
 */
@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {

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
            CustomerSettings settings) {
        CustomerSettings.Relay relay = settings.relay();
        return new OutboxRelay(
                unitOfWork,
                dsl,
                json,
                publisher,
                clock,
                meters,
                "customer",
                relay.batchSize(),
                relay.maxAttempts(),
                new Backoff(relay.backoffBase(), relay.backoffCap(), relay.jitter()));
    }

    /** The startup assertion: customer's one outbox consumer must be a bean, or every outbox row is delivered to nobody. */
    @Bean
    OutboxConsumers outboxConsumers(WebhookEventListener webhookListener) {
        return new OutboxConsumers(webhookListener);
    }

    public record OutboxConsumers(WebhookEventListener webhookListener) {

        public OutboxConsumers {
            Objects.requireNonNull(
                    webhookListener,
                    "no webhook listener is registered: the outbox relay would mark every row delivered and no "
                            + "delivery would ever be queued");
        }
    }
}
