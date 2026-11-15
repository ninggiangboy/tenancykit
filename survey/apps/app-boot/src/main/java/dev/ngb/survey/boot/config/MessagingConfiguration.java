package dev.ngb.survey.boot.config;

import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.replica.ReplicaStream;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Survey's part in the suite broker.
 *
 * <p>It consumes one stream — the five Platform state-transfer events on {@code survey.platform-events} — and produces
 * its own three facts on {@code survey.events}. It publishes no command and consumes no other app's event. The
 * envelope {@code schemaVersion} is <b>2</b>; a wrong value rejects every message.
 *
 * <p>{@code MessagingProperties.exchange} is the <em>producing</em> exchange, so it is {@code survey.events}. The
 * consumed exchange is declared through its queue binding; {@code SuiteTopology} declares every exchange a queue binds
 * to as well as the producing one.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /**
     * One queue and its dead-letter pair on the consuming side, and the three exports that matter on the producing side.
     * The export entries are what {@code BrokerRelay} resolves the exchange from; a misnamed property would make the
     * relay a silent no-op, so {@code OutboxConfiguration} asserts the route set is non-empty at startup.
     */
    @Bean
    MessagingProperties messagingProperties(SurveySettings settings) {
        SurveySettings.Relay relay = settings.relay();
        String exchange = settings.events().exchange();
        return new MessagingProperties(
                exchange,
                Map.of(
                        "survey-version.published", exchange,
                        "survey.closed", exchange,
                        "survey-response.submitted", exchange),
                List.of(new QueueBinding(
                        settings.messaging().queue(),
                        settings.messaging().exchange(),
                        ReplicaStream.eventTypes())),
                new Relay(
                        relay.batchSize(),
                        relay.maxAttempts(),
                        relay.backoffBase(),
                        relay.backoffCap(),
                        relay.jitter(),
                        relay.claimDeadline()),
                new Document(2));
    }

    @Bean
    Declarables surveyTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }
}
