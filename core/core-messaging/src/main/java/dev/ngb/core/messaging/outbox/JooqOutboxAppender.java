package dev.ngb.core.messaging.outbox;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventPublisher;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Writes an event to the outbox table inside the caller's transaction; the relay delivers it later. */
public class JooqOutboxAppender implements EventPublisher {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqOutboxAppender(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an event must be published inside a unit of work");
        }
        dsl.insertInto(MessagingTables.OUTBOX)
                .set(MessagingTables.OUTBOX_ID, event.eventId())
                .set(MessagingTables.OUTBOX_TENANT_ID, event.tenantId().value())
                .set(MessagingTables.OUTBOX_AGGREGATE, event.aggregate())
                .set(MessagingTables.OUTBOX_TYPE, event.type())
                .set(MessagingTables.OUTBOX_PAYLOAD, JSONB.valueOf(json.writeValueAsString(event.payload())))
                .set(MessagingTables.OUTBOX_CREATED_AT, event.occurredAt().atOffset(ZoneOffset.UTC))
                .set(MessagingTables.OUTBOX_NEXT_ATTEMPT_AT, event.occurredAt().atOffset(ZoneOffset.UTC))
                .execute();
    }
}
