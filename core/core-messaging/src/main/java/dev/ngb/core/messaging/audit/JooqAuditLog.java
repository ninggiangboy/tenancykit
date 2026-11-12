package dev.ngb.core.messaging.audit;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.runtime.IdGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Appends audit entries through the plain-SQL {@code audit_log} contract every service's migration satisfies. */
public class JooqAuditLog implements AuditLog {

    private static final Table<Record> AUDIT_LOG = DSL.table(DSL.name("audit_log"));
    private static final Field<UUID> ID = DSL.field(DSL.name("audit_log", "id"), SQLDataType.UUID);
    private static final Field<UUID> TENANT_ID = DSL.field(DSL.name("audit_log", "tenant_id"), SQLDataType.UUID);
    private static final Field<String> ACTOR = DSL.field(DSL.name("audit_log", "actor"), SQLDataType.VARCHAR);
    private static final Field<String> ACTION = DSL.field(DSL.name("audit_log", "action"), SQLDataType.VARCHAR);
    private static final Field<String> SUBJECT = DSL.field(DSL.name("audit_log", "subject"), SQLDataType.VARCHAR);
    private static final Field<String> REQUEST_ID = DSL.field(DSL.name("audit_log", "request_id"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> OCCURRED_AT =
            DSL.field(DSL.name("audit_log", "occurred_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;
    private final IdGenerator ids;

    public JooqAuditLog(DSLContext dsl, IdGenerator ids) {
        this.dsl = dsl;
        this.ids = ids;
    }

    @Override
    public void record(AuditEntry entry) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an audit entry must be recorded inside a unit of work");
        }
        dsl.insertInto(AUDIT_LOG)
                .set(ID, ids.newId())
                .set(TENANT_ID, entry.tenantId().value())
                .set(ACTOR, entry.actor())
                .set(ACTION, entry.action())
                .set(SUBJECT, entry.target())
                .set(REQUEST_ID, entry.requestId())
                .set(OCCURRED_AT, entry.occurredAt().atOffset(ZoneOffset.UTC))
                .execute();
    }
}
