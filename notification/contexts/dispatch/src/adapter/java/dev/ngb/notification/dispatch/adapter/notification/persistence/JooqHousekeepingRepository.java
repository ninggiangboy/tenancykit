package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.EMAIL_DISPATCHES;
import static dev.ngb.notification_jooq.Tables.NOTIFICATIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.port.HousekeepingRepository;
import java.time.Instant;
import org.jooq.DSLContext;

/** Bounded deletes only: each statement carries a limit, so a purge never locks a whole table. */
public class JooqHousekeepingRepository implements HousekeepingRepository {

    private final DSLContext dsl;

    public JooqHousekeepingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int deleteNotificationsBefore(TenantId tenantId, Instant cutoff, int batchSize) {
        return dsl.deleteFrom(NOTIFICATIONS)
                .where(NOTIFICATIONS.TENANT_ID.eq(tenantId.value()))
                .and(NOTIFICATIONS.CREATED_AT.lt(cutoff))
                .limit(batchSize)
                .execute();
    }

    @Override
    public int redactDispatchesBefore(TenantId tenantId, Instant cutoff, int batchSize) {
        var ids = dsl.select(EMAIL_DISPATCHES.ID)
                .from(EMAIL_DISPATCHES)
                .where(EMAIL_DISPATCHES.TENANT_ID.eq(tenantId.value()))
                .and(EMAIL_DISPATCHES.CREATED_AT.lt(cutoff))
                .and(EMAIL_DISPATCHES.SUBJECT.isNotNull())
                .limit(batchSize)
                .fetch(EMAIL_DISPATCHES.ID);
        if (ids.isEmpty()) {
            return 0;
        }
        return dsl.update(EMAIL_DISPATCHES)
                .set(EMAIL_DISPATCHES.SUBJECT, (String) null)
                .set(EMAIL_DISPATCHES.RECIPIENT, (String) null)
                .set(EMAIL_DISPATCHES.REASON, (String) null)
                .where(EMAIL_DISPATCHES.ID.in(ids))
                .execute();
    }
}
