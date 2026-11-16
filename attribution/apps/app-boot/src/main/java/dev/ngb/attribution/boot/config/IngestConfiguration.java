package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.tracking.adapter.ingest.amqp.ClickPublisher;
import dev.ngb.attribution.tracking.adapter.ingest.amqp.IngestFlusher;
import dev.ngb.attribution.tracking.adapter.ingest.memory.IngestBuffer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ingest path's wiring: one bounded buffer, the publisher that drains it, and the flusher thread.
 *
 * <p>The buffer is offered to by the redirect and drained into the ingest exchange by a single flusher. The flusher runs
 * wherever a click can be produced — both runtimes, because a co-located dev process both produces and consumes — and is
 * stopped with the context so a shutdown does not leave a thread behind.
 */
@Configuration(proxyBeanMethods = false)
class IngestConfiguration {

    @Bean
    IngestBuffer ingestBuffer(AttributionSettings settings, MeterRegistry meters) {
        AttributionSettings.Redirect redirect = settings.redirect();
        return new IngestBuffer(redirect.bufferSize(), redirect.flushBatchSize(), meters);
    }

    @Bean
    ClickPublisher clickPublisher(
            RabbitTemplate rabbit, JsonMapper json, AttributionSettings settings, MeterRegistry meters) {
        return new ClickPublisher(rabbit, json, settings.ingest().exchange(), meters);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    IngestFlusher ingestFlusher(
            IngestBuffer buffer, ClickPublisher publisher, AttributionSettings settings) {
        return new IngestFlusher(buffer, publisher, settings.redirect().flushInterval());
    }
}
