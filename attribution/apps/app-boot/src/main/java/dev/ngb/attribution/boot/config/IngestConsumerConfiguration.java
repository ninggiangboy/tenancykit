package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.boot.rollup.RollupCursor;
import dev.ngb.attribution.tracking.adapter.ingest.amqp.IngestConsumer;
import dev.ngb.attribution.tracking.adapter.ingest.persistence.JooqClickStore;
import dev.ngb.attribution.tracking.adapter.ingest.persistence.JooqPartitionStore;
import dev.ngb.attribution.tracking.application.ingest.command.IngestClicksUseCase;
import dev.ngb.attribution.tracking.application.ingest.port.ClickStore;
import dev.ngb.attribution.tracking.application.ingest.port.PartitionStore;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ingest consumer's wiring: the store, the partition maintenance, the use case and the batch listener.
 *
 * <p>The listener consumes in batches and acks only after the batch's transaction committed, so a crash mid-batch
 * redelivers it and the store's idempotent insert makes the redelivery harmless. It runs only in the worker runtime: the
 * api runtime produces clicks and never consumes them.
 */
@Configuration(proxyBeanMethods = false)
class IngestConsumerConfiguration {

    @Bean
    ClickStore clickStore(DSLContext dsl) {
        return new JooqClickStore(dsl);
    }

    @Bean
    PartitionStore partitionStore(DSLContext dsl) {
        return new JooqPartitionStore(dsl);
    }

    @Bean
    IngestClicksUseCase ingestClicksUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ClickStore clickStore,
            @Value("${attribution.ingest.attempts:3}") int attempts) {
        return new IngestClicksUseCase(unitOfWork, tenantContext, clickStore, attempts);
    }

    @Bean
    RollupCursor rollupCursor() {
        // No rollup exists yet; retention reads this and drops nothing rather than removing a day no report has read.
        return RollupCursor.none();
    }

    @Bean
    IngestConsumer ingestConsumer(
            IngestClicksUseCase useCase, JsonMapper json, RabbitTemplate rabbit, MeterRegistry meters) {
        return new IngestConsumer(useCase, json, rabbit, "suite.dlx", meters);
    }

    /**
     * The batch listener. {@code MANUAL} acknowledgement is not used: the container acks after the listener returns
     * normally and requeues on a throw, which is exactly the per-batch contract the use case provides.
     */
    @Bean
    @Profile("worker")
    SimpleMessageListenerContainer ingestContainer(
            ConnectionFactory connectionFactory,
            IngestConsumer consumer,
            AttributionSettings settings,
            @Value("${attribution.ingest.batch-size:500}") int batchSize,
            @Value("${attribution.ingest.receive-timeout:PT0.2S}") java.time.Duration receiveTimeout) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("attribution-ingest");
        container.setQueueNames(settings.ingest().queue());
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setBatchSize(batchSize);
        container.setConsumerBatchEnabled(true);
        container.setDeBatchingEnabled(false);
        container.setReceiveTimeout(receiveTimeout.toMillis());
        container.setMessageListener((org.springframework.amqp.core.BatchMessageListener)
                (List<Message> messages) -> consumer.consume(messages));
        return container;
    }
}
