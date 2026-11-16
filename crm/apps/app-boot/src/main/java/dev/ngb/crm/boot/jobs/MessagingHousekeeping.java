package dev.ngb.crm.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.MessagingTables;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.boot.config.CrmSettings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.context.annotation.Profile;

/**
 * Bounded deletes for the two messaging tables the wiring layer owns: the consumer's inbox and the outbox rows whose
 * life is over. No context reaches them, and this class reaches no context's table.
 *
 * <p>The inbox row is deduplication state and expires with its retention. An outbox row is deleted once the relay
 * delivered it, or once the relay gave up on it: a payload carries the customer's reference and the fact it describes,
 * so a row the consumers already handled would keep that data past the delivery that carries the same body.
 */
@Profile("worker")
public class MessagingHousekeeping {

    private static final Field<OffsetDateTime> INBOX_PROCESSED_AT =
            DSL.field(DSL.name("event_inbox", "processed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    /** A row the relay gave up on is dead whichever path marked it: the in-process one or the broker's. */
    private static final Condition DEAD =
            MessagingTables.OUTBOX_STATUS.eq("DEAD").or(MessagingTables.OUTBOX_PUBLISH_STATUS.eq("DEAD"));

    /** @param relayed rows their consumers already handled, {@code dead} rows no consumer ever will */
    public record Purged(int inbox, int relayed, int dead) {}

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final CrmSettings.Housekeeping settings;
    private final Clock clock;

    public MessagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, CrmSettings.Housekeeping settings, Clock clock) {
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
            int relayed = dsl.deleteFrom(MessagingTables.OUTBOX)
                    .where(MessagingTables.OUTBOX_TENANT_ID.eq(tenantId.value()))
                    .and(MessagingTables.OUTBOX_STATUS.eq("DELIVERED"))
                    .and(MessagingTables.OUTBOX_CREATED_AT.lt(
                            now.minus(settings.relayedOutboxRetention()).atOffset(ZoneOffset.UTC)))
                    .limit(settings.batchSize())
                    .execute();
            int dead = dsl.deleteFrom(MessagingTables.OUTBOX)
                    .where(MessagingTables.OUTBOX_TENANT_ID.eq(tenantId.value()))
                    .and(DEAD)
                    .and(MessagingTables.OUTBOX_CREATED_AT.lt(
                            now.minus(settings.deadOutboxRetention()).atOffset(ZoneOffset.UTC)))
                    .limit(settings.batchSize())
                    .execute();
            return new Purged(inbox, relayed, dead);
        });
    }
}
