package dev.ngb.mailroom.boot.config;

import dev.ngb.core.messaging.inbound.AmqpInboundDispatcher;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The mailer result queue. It runs only in the worker, the runtime that owns every consumer; the API serves HTTP and
 * consumes nothing.
 */
@Configuration(proxyBeanMethods = false)
class MailerEventsConfiguration {

    @Bean
    @Profile("worker")
    SimpleMessageListenerContainer mailerEventsContainer(
            ConnectionFactory connectionFactory, AmqpInboundDispatcher dispatcher, MailroomSettings settings) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("email-mailer-events");
        container.setQueueNames(settings.mailer().queue());
        container.setMessageListener(message -> dispatcher.onMessage(message.getBody()));
        return container;
    }
}
