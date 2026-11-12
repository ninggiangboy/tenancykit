package dev.ngb.core.messaging.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * The tables core writes, addressed by plain SQL rather than a service's generated schema. The column contract
 * every consuming service's migration must satisfy lives in {@code schema-contract.md} next to this package.
 */
final class MessagingTables {

    static final Table<Record> OUTBOX = DSL.table(DSL.name("outbox"));
    static final Field<UUID> OUTBOX_ID = DSL.field(DSL.name("outbox", "id"), SQLDataType.UUID);
    static final Field<UUID> OUTBOX_TENANT_ID = DSL.field(DSL.name("outbox", "tenant_id"), SQLDataType.UUID);
    static final Field<String> OUTBOX_AGGREGATE = DSL.field(DSL.name("outbox", "aggregate"), SQLDataType.VARCHAR);
    static final Field<String> OUTBOX_TYPE = DSL.field(DSL.name("outbox", "type"), SQLDataType.VARCHAR);
    static final Field<JSONB> OUTBOX_PAYLOAD = DSL.field(DSL.name("outbox", "payload"), SQLDataType.JSONB);
    static final Field<OffsetDateTime> OUTBOX_CREATED_AT =
            DSL.field(DSL.name("outbox", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    static final Field<String> OUTBOX_STATUS = DSL.field(DSL.name("outbox", "status"), SQLDataType.VARCHAR);
    static final Field<Integer> OUTBOX_ATTEMPTS = DSL.field(DSL.name("outbox", "attempts"), SQLDataType.INTEGER);
    static final Field<OffsetDateTime> OUTBOX_NEXT_ATTEMPT_AT =
            DSL.field(DSL.name("outbox", "next_attempt_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    static final Field<String> OUTBOX_LAST_ERROR = DSL.field(DSL.name("outbox", "last_error"), SQLDataType.VARCHAR);

    static final Table<Record> EVENT_INBOX = DSL.table(DSL.name("event_inbox"));
    static final Field<UUID> EVENT_INBOX_TENANT_ID = DSL.field(DSL.name("event_inbox", "tenant_id"), SQLDataType.UUID);
    static final Field<String> EVENT_INBOX_CONSUMER = DSL.field(DSL.name("event_inbox", "consumer"), SQLDataType.VARCHAR);
    static final Field<UUID> EVENT_INBOX_EVENT_ID = DSL.field(DSL.name("event_inbox", "event_id"), SQLDataType.UUID);

    private MessagingTables() {}
}
