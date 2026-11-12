package dev.ngb.core.messaging.outbox;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventInbox;
import org.jooq.DSLContext;

/** The primary key (tenant, consumer, event) turns a repeated event into a no-op insert. */
public class JooqEventInbox implements EventInbox {

    private final DSLContext dsl;

    public JooqEventInbox(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean firstTime(String consumer, DomainEvent event) {
        return dsl.insertInto(MessagingTables.EVENT_INBOX)
                .set(MessagingTables.EVENT_INBOX_TENANT_ID, event.tenantId().value())
                .set(MessagingTables.EVENT_INBOX_CONSUMER, consumer)
                .set(MessagingTables.EVENT_INBOX_EVENT_ID, event.eventId())
                .onConflict(
                        MessagingTables.EVENT_INBOX_TENANT_ID,
                        MessagingTables.EVENT_INBOX_CONSUMER,
                        MessagingTables.EVENT_INBOX_EVENT_ID)
                .doNothing()
                .execute() == 1;
    }
}
