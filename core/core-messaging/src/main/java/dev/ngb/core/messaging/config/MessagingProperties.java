package dev.ngb.core.messaging.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How one service takes part in the suite's broker: the exchange it produces to, which event types it exports,
 * the queues it consumes, and the relay's retry policy. Built by the application from its own configuration so
 * core never reads a property source.
 *
 * @param exchange the topic exchange this service's events are published to
 * @param exports the event types this service exports, mapped to the exchange each goes to
 * @param queues the queues this service consumes; a producer passes none
 * @param relay the broker relay's batch and retry policy
 */
public record MessagingProperties(String exchange, Map<String, String> exports, List<QueueBinding> queues, Relay relay) {

    public MessagingProperties {
        Objects.requireNonNull(exchange, "exchange");
        if (exchange.isBlank()) {
            throw new IllegalArgumentException("suite.messaging.exchange must name the producer exchange");
        }
        exports = Map.copyOf(exports);
        queues = List.copyOf(queues);
        Objects.requireNonNull(relay, "relay");
    }

    /**
     * @param name the durable queue name, for example {@code billing.platform-events}
     * @param exchange the exchange the queue binds to
     * @param routingKeys the event types the queue accepts, or a pattern such as {@code #}
     */
    public record QueueBinding(String name, String exchange, List<String> routingKeys) {

        public QueueBinding {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(exchange, "exchange");
            routingKeys = List.copyOf(routingKeys);
        }
    }

    /**
     * @param batchSize how many due rows one poll claims at most
     * @param maxAttempts how many publish attempts before a row becomes {@code DEAD}
     * @param backoffBase the first retry delay, doubled per attempt up to {@code backoffCap}
     * @param backoffCap the longest retry delay
     */
    public record Relay(int batchSize, int maxAttempts, Duration backoffBase, Duration backoffCap) {

        public Relay {
            Objects.requireNonNull(backoffBase, "backoffBase");
            Objects.requireNonNull(backoffCap, "backoffCap");
            if (batchSize < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("batch-size and max-attempts must be positive");
            }
        }
    }
}
