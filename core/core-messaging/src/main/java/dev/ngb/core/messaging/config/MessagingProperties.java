package dev.ngb.core.messaging.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How one service takes part in the suite's broker: the exchange it produces to, which event types it exports,
 * the queues it consumes, the relay's retry policy and the envelope document it speaks. Built by the application
 * from its own configuration so core never reads a property source.
 *
 * @param exchange the topic exchange this service's events are published to
 * @param exports the event types this service exports, mapped to the exchange each goes to
 * @param queues the queues this service consumes; a producer passes none
 * @param relay the broker relay's batch and retry policy
 * @param document the envelope document this service produces and reads
 */
public record MessagingProperties(String exchange, Map<String, String> exports, List<QueueBinding> queues, Relay relay, Document document) {

    public MessagingProperties {
        Objects.requireNonNull(exchange, "exchange");
        if (exchange.isBlank()) {
            throw new IllegalArgumentException("suite.messaging.exchange must name the producer exchange");
        }
        exports = Map.copyOf(exports);
        queues = List.copyOf(queues);
        Objects.requireNonNull(relay, "relay");
        Objects.requireNonNull(document, "document");
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
     * @param jitter the +/- spread applied to a retry delay, in {@code [0, 1)}
     * @param claimDeadline how long a claimed row stays leased before another worker may take it again
     */
    public record Relay(int batchSize, int maxAttempts, Duration backoffBase, Duration backoffCap, double jitter, Duration claimDeadline) {

        public Relay {
            Objects.requireNonNull(backoffBase, "backoffBase");
            Objects.requireNonNull(backoffCap, "backoffCap");
            Objects.requireNonNull(claimDeadline, "claimDeadline");
            if (batchSize < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("batch-size and max-attempts must be positive");
            }
            if (jitter < 0 || jitter >= 1) {
                throw new IllegalArgumentException("jitter must be in [0, 1)");
            }
            if (claimDeadline.isNegative() || claimDeadline.isZero()) {
                throw new IllegalArgumentException("claim-deadline must be positive");
            }
        }
    }

    /**
     * @param schemaVersion the envelope version this service produces; a consumer rejects any other version
     */
    public record Document(int schemaVersion) {

        public Document {
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schema-version must be positive");
            }
        }
    }
}
