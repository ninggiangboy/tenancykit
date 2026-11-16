package dev.ngb.booking.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox: a fact is written in the same transaction as the change it reports.
 *
 * <p>Booking declares no exchange, so there is no broker relay here. The in-process webhook relay that drains the
 * outbox to signed HTTP deliveries arrives with the webhook context; until then nothing writes a row.
 */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    @Bean
    EventPublisher eventPublisher(DSLContext dsl, JsonMapper json) {
        return new JooqOutboxAppender(dsl, json);
    }
}
