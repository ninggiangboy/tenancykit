package dev.ngb.notification.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.MessagingTables;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.notification.dispatch.application.notification.service.HousekeepingSettings;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Bounded deletes for the two tables the wiring layer owns: the consumer's inbox and the dead outbox rows. The
 * dispatch context never reaches them, and this class never reaches a dispatch table.
 */
@org.springframework.context.annotation.Profile("worker")
public class MessagingHousekeeping {

    private static final Field<java.time.OffsetDateTime> INBOX_PROCESSED_AT =
            DSL.field(DSL.name("event_inbox", "processed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    @org.springframework.context.annotation.Profile("worker")
    public record Purged(int inbox, int outbox) {}

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final HousekeepingSettings settings;
    private final Clock clock;

    public MessagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, HousekeepingSettings settings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.settings = settings;
        this.clock = clock;
    }

    public Purged runOnce(TenantId tenantId) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            int inbox = dsl.deleteFrom(MessagingTables.EVENT_INBOX)
                    .where(MessagingTables.EVENT_INBOX_TENANT_ID.eq(tenantId.value()))
                    .and(INBOX_PROCESSED_AT.lt(now.minus(settings.inboxRetention()).atOffset(ZoneOffset.UTC)))
                    .limit(settings.batchSize())
                    .execute();
            int outbox = dsl.deleteFrom(MessagingTables.OUTBOX)
                    .where(MessagingTables.OUTBOX_TENANT_ID.eq(tenantId.value()))
                    .and(MessagingTables.OUTBOX_PUBLISH_STATUS.eq("DEAD"))
                    .and(MessagingTables.OUTBOX_CREATED_AT.lt(
                            now.minus(settings.deadOutboxRetention()).atOffset(ZoneOffset.UTC)))
                    .limit(settings.batchSize())
                    .execute();
            return new Purged(inbox, outbox);
        });
    }
}
