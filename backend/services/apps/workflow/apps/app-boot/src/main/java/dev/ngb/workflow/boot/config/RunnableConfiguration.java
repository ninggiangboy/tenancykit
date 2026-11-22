package dev.ngb.workflow.boot.config;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.runtime.application.runtime.service.TokenSweeper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The wake-up queue: {@code workflow.runnable}, this app's own internal queue, declared with the suite's dead-letter
 * pair and bound to no exchange because only this app publishes to it.
 *
 * <p>A message names the tenant that has work, not a token: losing one costs the sweeper's interval, never correctness,
 * because the rows in Postgres are the truth. The api runtime publishes; the worker runtime consumes.
 */
@Configuration(proxyBeanMethods = false)
class RunnableConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(RunnableConfiguration.class);

    static final String QUEUE = "workflow.runnable";

    @Bean
    Declarables runnableQueue() {
        String deadLetter = QUEUE + ".dlq";
        return new Declarables(
                new TopicExchange("suite.dlx", true, false),
                QueueBuilder.durable(QUEUE)
                        .deadLetterExchange("suite.dlx")
                        .deadLetterRoutingKey(QUEUE)
                        .build(),
                QueueBuilder.durable(deadLetter).build(),
                new Binding(deadLetter, Binding.DestinationType.QUEUE, "suite.dlx", QUEUE, null));
    }

    @Bean
    WakeUpPublisher wakeUpPublisher(RabbitTemplate rabbit) {
        return tenantId -> rabbit.convertAndSend("", QUEUE, tenantId.value().toString());
    }

    /** The worker's consumer: the tenant in the message is the scope, and the loop stops when nothing is due. */
    @Bean
    @Profile("worker")
    SimpleMessageListenerContainer runnableContainer(
            ConnectionFactory connectionFactory,
            TenantContext tenantContext,
            ClaimAndRun claimAndRun,
            TokenSweeper sweeper,
            WorkflowSettings settings) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("workflow-runnable");
        container.setQueueNames(QUEUE);
        container.setMessageListener(message -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                TenantId tenant = new TenantId(UUID.fromString(body));
                tenantContext.runAs(RequestScope.tenant(tenant), () -> {
                    sweeper.requeueExpired();
                    claimAndRun.drain(settings.runtime().batch());
                });
            } catch (RuntimeException failure) {
                // A malformed or failing message must not loop; the sweeper still finds the due rows.
                LOG.warn("could not run the workflow wake-up for {}", body, failure);
            }
        });
        return container;
    }
}
