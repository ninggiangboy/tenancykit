package dev.ngb.mailer.boot.config;

import dev.ngb.core.event.EventInbox;
import dev.ngb.core.messaging.broker.EnvelopeCodec;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.inbound.AmqpInboundDispatcher;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailer.boot.config.MailerSettings.Delivery;
import dev.ngb.mailer.email.adapter.delivery.messaging.CommandSchemaValidator;
import dev.ngb.mailer.email.adapter.delivery.messaging.EmailCommandHandler;
import dev.ngb.mailer.email.adapter.delivery.messaging.OutboxDeliveryResultPublisher;
import dev.ngb.mailer.email.application.email.command.AcceptEmailCommandUseCase;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mailer's part in the suite broker: it consumes commands and publishes one event, the terminal delivery result.
 *
 * <p>The producing exchange is {@code mailer.events}, not {@code mailer.commands}: {@code MessagingProperties.exchange}
 * is where this service's <em>events</em> go, and the command exchange is declared through the queue binding instead,
 * because mailer is a consumer there. {@code exports} maps the one event type to that exchange; an empty map would make
 * the broker relay a silent no-op.
 *
 * <p>Two values in here are not defaults a reader should trust to be free: the envelope {@code schemaVersion} is
 * <b>2</b>, and a wrong value rejects every command; and {@code EnvelopeCodec} is not a bean in {@code core}, so it has
 * to be constructed from the {@code JsonMapper} here or the context fails at startup with an unsatisfied dependency.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    /**
     * One producing exchange, one consumed queue, one dead-letter pair. The export entry is what {@code BrokerRelay}
     * resolves the exchange from; {@code OutboxConfiguration} asserts the route set is non-empty at startup.
     */
    @Bean
    MessagingProperties messagingProperties(MailerSettings settings) {
        Delivery delivery = settings.delivery();
        return new MessagingProperties(
                settings.messaging().eventsExchange(),
                Map.of(OutboxDeliveryResultPublisher.TYPE, settings.messaging().eventsExchange()),
                List.of(new QueueBinding(
                        settings.messaging().queue(), settings.messaging().exchange(), List.of("email.send"))),
                new Relay(
                        delivery.batchSize(),
                        delivery.maxAttempts(),
                        delivery.backoffBase(),
                        delivery.backoffCap(),
                        delivery.jitter(),
                        delivery.lease()),
                new Document(2));
    }

    @Bean
    Declarables mailerTopology(MessagingProperties properties) {
        return SuiteTopology.declarables(properties);
    }

    /**
     * The validator loads the contract once, in its constructor, so a packaging mistake fails the context at startup
     * instead of every message at runtime.
     */
    @Bean
    CommandSchemaValidator commandSchemaValidator(JsonMapper json) {
        return new CommandSchemaValidator(json);
    }

    /**
     * The one handler. Without this bean the dispatcher has no handler for {@code email.send} and acknowledges every
     * command in silence — which is exactly what a test asserts against.
     */
    @Bean
    EmailCommandHandler emailCommandHandler(
            CommandSchemaValidator validator, AcceptEmailCommandUseCase acceptEmailCommand) {
        return new EmailCommandHandler(validator, acceptEmailCommand);
    }

    /**
     * The dispatcher has six collaborators, and every one of them is required: the codec, the handlers, the inbox, the
     * unit of work, the tenant context and the retry template.
     */
    @Bean
    @Profile("worker")
    AmqpInboundDispatcher inboundDispatcher(
            JsonMapper json,
            List<InboundEventHandler> handlers,
            EventInbox inbox,
            UnitOfWork unitOfWork,
            TenantContext tenantContext) {
        return new AmqpInboundDispatcher(
                new EnvelopeCodec(json), handlers, inbox, unitOfWork, tenantContext, AmqpInboundDispatcher.defaultRetry());
    }

    /**
     * The commands consumer. It is a worker concern: the migrate runtime must not read a queue, and a co-located api
     * runtime does not exist here.
     */
    @Bean
    @Profile("worker")
    SimpleMessageListenerContainer mailerCommandsContainer(
            ConnectionFactory connectionFactory, AmqpInboundDispatcher dispatcher, MailerSettings settings) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("mailer-commands");
        container.setQueueNames(settings.messaging().queue());
        container.setMessageListener(message -> dispatcher.onMessage(message.getBody()));
        return container;
    }
}
