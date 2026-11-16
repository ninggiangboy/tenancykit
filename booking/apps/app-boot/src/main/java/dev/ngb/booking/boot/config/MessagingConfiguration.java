package dev.ngb.booking.boot.config;

import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Booking's part in the suite broker: consume only.
 *
 * <p>It declares one queue, {@code booking.platform-events}, bound to the five Platform state-transfer keys, and the
 * queue's dead-letter pair, so a message booking cannot process lands in {@code booking.platform-events.dlq} instead
 * of looping. It declares <b>no exchange of its own</b> and binds to no business exchange: booking's ten facts leave as
 * signed webhooks, written to the outbox and delivered in process, so there is no exchange and no export route.
 *
 * <p>{@code platform.events} is Platform's exchange and is declared by Platform. Declaring it here would also be
 * refused by booking's broker user, which holds {@code read} on it and no {@code configure}. The suite's one
 * dead-letter exchange, {@code suite.dlx}, is declared because a queue that dead-letters names it.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    @Bean
    Declarables bookingTopology(BookingSettings settings) {
        BookingSettings.Messaging messaging = settings.messaging();
        String queueName = messaging.queue();
        String deadLetterQueue = queueName + ".dlq";
        List<Declarable> items = new ArrayList<>();
        items.add(new TopicExchange(SuiteTopology.DEAD_LETTER_EXCHANGE, true, false));
        items.add(QueueBuilder.durable(queueName)
                .deadLetterExchange(SuiteTopology.DEAD_LETTER_EXCHANGE)
                // Without this the broker keeps the original routing key when it dead-letters, and the DLQ
                // binding by queue name would never match.
                .deadLetterRoutingKey(queueName)
                .build());
        for (String type : ReplicaStream.eventTypes()) {
            items.add(new Binding(queueName, Binding.DestinationType.QUEUE, messaging.exchange(), type, null));
        }
        items.add(QueueBuilder.durable(deadLetterQueue).build());
        items.add(new Binding(
                deadLetterQueue, Binding.DestinationType.QUEUE, SuiteTopology.DEAD_LETTER_EXCHANGE, queueName, null));
        Queue declared = (Queue) items.get(1);
        if (!declared.isDurable()) {
            throw new IllegalStateException(
                    "booking.platform-events must be durable: a missing queue would drop replica events");
        }
        return new Declarables(items);
    }
}
