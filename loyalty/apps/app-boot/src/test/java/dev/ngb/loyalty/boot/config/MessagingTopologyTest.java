package dev.ngb.loyalty.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * The broker topology is consume-only and nothing more: one durable queue bound to the five Platform keys, its
 * dead-letter queue, and the suite's dead-letter exchange. No exchange of loyalty's own is declared, because loyalty has
 * none — its facts leave as signed webhooks.
 */
class MessagingTopologyTest {

    private static final LoyaltySettings SETTINGS = settings("platform.events", "loyalty.platform-events");

    @Test
    void onlyTheSuiteDeadLetterExchangeIsDeclared() {
        assertThat(exchanges())
                .as("an exchange of its own would let another app bind to a loyalty fact")
                .containsExactly(SuiteTopology.DEAD_LETTER_EXCHANGE);
    }

    @Test
    void theQueueIsBoundToTheFivePlatformKeysAndNothingElse() {
        List<Binding> bindings = declarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(binding -> binding.getDestination().equals("loyalty.platform-events"))
                .toList();

        assertThat(bindings).extracting(Binding::getExchange).containsOnly("platform.events");
        assertThat(bindings).extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrderElementsOf(ReplicaStream.eventTypes());
        assertThat(ReplicaStream.eventTypes()).hasSize(5);
    }

    @Test
    void theQueueCarriesTheSuiteDeadLetterPair() {
        Queue queue = queues().stream()
                .filter(candidate -> candidate.getName().equals("loyalty.platform-events"))
                .findFirst()
                .orElseThrow();
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", SuiteTopology.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", "loyalty.platform-events");

        assertThat(queues()).extracting(Queue::getName).contains("loyalty.platform-events.dlq");
        assertThat(declarables().stream()
                        .filter(Binding.class::isInstance)
                        .map(Binding.class::cast)
                        .filter(binding -> binding.getDestination().equals("loyalty.platform-events.dlq"))
                        .toList())
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.getExchange()).isEqualTo(SuiteTopology.DEAD_LETTER_EXCHANGE);
                    assertThat(binding.getRoutingKey()).isEqualTo("loyalty.platform-events");
                });
    }

    @Test
    void nothingElseIsDeclared() {
        assertThat(queues()).extracting(Queue::getName)
                .containsExactlyInAnyOrder("loyalty.platform-events", "loyalty.platform-events.dlq");
        assertThat(declarables()).hasSize(1 + 1 + 5 + 1 + 1);
    }

    private static Set<String> exchanges() {
        return declarables().stream()
                .filter(TopicExchange.class::isInstance)
                .map(TopicExchange.class::cast)
                .map(TopicExchange::getName)
                .collect(Collectors.toSet());
    }

    private static List<Queue> queues() {
        return declarables().stream().filter(Queue.class::isInstance).map(Queue.class::cast).toList();
    }

    private static List<Declarable> declarables() {
        return MessagingConfiguration.platformEventsTopology(SETTINGS).getDeclarables().stream().toList();
    }

    private static LoyaltySettings settings(String exchange, String queue) {
        Duration noWait = Duration.ZERO;
        return new LoyaltySettings(
                new LoyaltySettings.Messaging(exchange, queue),
                new LoyaltySettings.Relay(Duration.ofSeconds(2), 10, 3, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.2),
                new LoyaltySettings.Webhooks(false, Duration.ofSeconds(2), 10, 3, Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofSeconds(30), 10, 5, noWait, noWait, noWait, noWait, "", "k1", Duration.ofDays(30)),
                new LoyaltySettings.Jobs(true, 2),
                new LoyaltySettings.Expiry(100, Duration.ofHours(1)),
                new LoyaltySettings.TierEvaluation(100, Duration.ofDays(1)),
                new LoyaltySettings.Orders(500),
                new LoyaltySettings.Housekeeping(true, Duration.ofDays(30), Duration.ofDays(7), 100, Duration.ofHours(1)));
    }
}
