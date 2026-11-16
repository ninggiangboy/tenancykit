package dev.ngb.loyalty.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.MessagingTables;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.boot.config.LoyaltySettings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.context.annotation.Profile;

/**
 * Bounded deletes for the two tables the wiring layer owns: the consumer's inbox and the dead outbox rows. No context
 * reaches them, and this class reaches no context table.
 */
@Profile("worker")
public class MessagingHousekeeping {

    private static final Field<OffsetDateTime> INBOX_PROCESSED_AT =
            DSL.field(DSL.name("event_inbox", "processed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    @Profile("worker")
    public record Purged(int inbox, int outbox) {}

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final LoyaltySettings.Housekeeping settings;
    private final Clock clock;

    public MessagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, LoyaltySettings.Housekeeping settings, Clock clock) {
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
                    .and(MessagingTables.OUTBOX_STATUS.eq("DEAD"))
                    .and(MessagingTables.OUTBOX_CREATED_AT.lt(
                            now.minus(settings.deadOutboxRetention()).atOffset(ZoneOffset.UTC)))
                    .limit(settings.batchSize())
                    .execute();
            return new Purged(inbox, outbox);
        });
    }
}
