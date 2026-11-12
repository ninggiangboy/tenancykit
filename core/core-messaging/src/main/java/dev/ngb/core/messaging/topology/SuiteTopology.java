package dev.ngb.core.messaging.topology;

import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Builds the exchanges, queues, bindings and dead-letter wiring one service needs, from its configuration. A
 * producer declares its exchange; a consumer declares its queue, its bindings and the queue's dead-letter pair.
 * Every declaration is idempotent, so startup order does not matter.
 */
public final class SuiteTopology {

    /** One dead-letter exchange for the suite; a queue dead-letters with its own name as the routing key. */
    public static final String DEAD_LETTER_EXCHANGE = "suite.dlx";

    private SuiteTopology() {}

    public static Declarables declarables(MessagingProperties properties) {
        List<Declarable> items = new ArrayList<>();
        Set<String> exchanges = new LinkedHashSet<>();
        exchanges.add(properties.exchange());
        properties.queues().forEach(queue -> exchanges.add(queue.exchange()));
        exchanges.add(DEAD_LETTER_EXCHANGE);
        for (String exchange : exchanges) {
            items.add(new TopicExchange(exchange, true, false));
        }
        for (QueueBinding binding : properties.queues()) {
            Queue queue = QueueBuilder.durable(binding.name())
                    .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                    // Without this the broker keeps the original routing key when it dead-letters, and the DLQ
                    // binding by queue name would never match.
                    .deadLetterRoutingKey(binding.name())
                    .build();
            items.add(queue);
            for (String routingKey : binding.routingKeys()) {
                items.add(new Binding(binding.name(), Binding.DestinationType.QUEUE, binding.exchange(), routingKey, null));
            }
            String deadLetterQueue = binding.name() + ".dlq";
            items.add(QueueBuilder.durable(deadLetterQueue).build());
            items.add(new Binding(deadLetterQueue, Binding.DestinationType.QUEUE, DEAD_LETTER_EXCHANGE, binding.name(), null));
        }
        return new Declarables(items);
    }
}
