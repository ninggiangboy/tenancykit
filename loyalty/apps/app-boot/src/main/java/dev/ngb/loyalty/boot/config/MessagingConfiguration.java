package dev.ngb.loyalty.boot.config;

import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.core.runtime.Clock;
import dev.ngb.loyalty.boot.jobs.MessagingHousekeeping;
import dev.ngb.core.runtime.UnitOfWork;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Profile;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Loyalty's part in the suite broker: consume only.
 *
 * <p>One queue, {@code loyalty.platform-events}, bound to the five Platform state-transfer keys, with the suite's
 * dead-letter pair. Nothing else is declared and no exchange of loyalty's own exists: its facts leave as signed
 * webhooks, so there is no exchange for another app to bind to and no {@code BrokerRelay} to publish through. The
 * broker user's grants enforce the same rule — it may read {@code platform.events} and write only its own queue and
 * {@code suite.dlx}.
 *
 * <p>{@link SuiteTopology} is not used here because it always declares the producing exchange; loyalty has none, so the
 * same declarations are built from the same names ({@code suite.dlx}, {@code <queue>.dlq}, the queue name as the
 * dead-letter routing key) without inventing an exchange.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    static Declarables platformEventsTopology(LoyaltySettings settings) {
        String queue = settings.messaging().queue();
        String exchange = settings.messaging().exchange();
        String deadLetterExchange = SuiteTopology.DEAD_LETTER_EXCHANGE;
        List<Declarable> items = new ArrayList<>();
        // The one exchange declared is the suite's dead-letter exchange, which every app declares and which carries
        // nothing but dead-lettered messages.
        items.add(new TopicExchange(deadLetterExchange, true, false));
        items.add(QueueBuilder.durable(queue)
                .deadLetterExchange(deadLetterExchange)
                // Without this the broker keeps the original routing key when it dead-letters, and the DLQ binding by
                // queue name would never match.
                .deadLetterRoutingKey(queue)
                .build());
        for (String eventType : ReplicaStream.eventTypes()) {
            items.add(new Binding(queue, Binding.DestinationType.QUEUE, exchange, eventType, null));
        }
        String deadLetterQueue = queue + ".dlq";
        items.add(new Queue(deadLetterQueue, true));
        items.add(new Binding(deadLetterQueue, Binding.DestinationType.QUEUE, deadLetterExchange, queue, null));
        return new Declarables(items);
    }

    @Bean
    Declarables loyaltyTopology(LoyaltySettings settings) {
        return platformEventsTopology(settings);
    }

    /** The bounded deletes for the two tables the wiring layer owns; the worker is the only runtime that purges. */
    @Bean
    @Profile("worker")
    MessagingHousekeeping messagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, LoyaltySettings settings, Clock clock) {
        return new MessagingHousekeeping(unitOfWork, dsl, settings.housekeeping(), clock);
    }

}
