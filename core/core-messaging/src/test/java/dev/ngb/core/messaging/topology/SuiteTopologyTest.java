package dev.ngb.core.messaging.topology;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.TopicExchange;

/** Which exchanges a service declares: its own, never one that another service owns. */
class SuiteTopologyTest {

    private static final Relay RELAY = new Relay(10, 3, Duration.ofSeconds(1), Duration.ofSeconds(30), 0.1, Duration.ofMinutes(1));

    @Test
    void aBindingDeclaresItsExchangeByDefault() {
        var properties = properties("billing.events", new QueueBinding("billing.platform-events", "platform.events", List.of("a")));

        assertThat(exchangeNames(properties)).containsExactly("billing.events", "platform.events", SuiteTopology.DEAD_LETTER_EXCHANGE);
    }

    @Test
    void aBindingToAForeignExchangeBindsToItWithoutDeclaringIt() {
        var properties = properties(
                "survey.events",
                QueueBinding.toForeignExchange("survey.platform-events", "platform.events", List.of("a", "b")));

        assertThat(exchangeNames(properties))
                .as("a broker user may bind to an exchange it does not own, but is refused when it declares it")
                .containsExactly("survey.events", SuiteTopology.DEAD_LETTER_EXCHANGE);
        assertThat(SuiteTopology.declarables(properties).getDeclarables())
                .filteredOn(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filteredOn(binding -> binding.getExchange().equals("platform.events"))
                .extracting(Binding::getRoutingKey)
                .containsExactly("a", "b");
    }

    @Test
    void theProducerAndDeadLetterExchangesAreDeclaredWhateverTheBindingsSay() {
        var properties = properties("mailer.commands", QueueBinding.toForeignExchange("q", "platform.events", List.of("a")));

        assertThat(exchangeNames(properties)).contains("mailer.commands", SuiteTopology.DEAD_LETTER_EXCHANGE);
    }

    private static MessagingProperties properties(String producerExchange, QueueBinding binding) {
        return new MessagingProperties(producerExchange, Map.of(), List.of(binding), RELAY, new Document(2));
    }

    private static List<String> exchangeNames(MessagingProperties properties) {
        Collection<Declarable> declarables = SuiteTopology.declarables(properties).getDeclarables();
        return declarables.stream()
                .filter(TopicExchange.class::isInstance)
                .map(TopicExchange.class::cast)
                .map(TopicExchange::getName)
                .toList();
    }
}
