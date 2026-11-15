package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.EMAIL_DISPATCHES;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.query.DispatchReadRepository;
import dev.ngb.notification.dispatch.application.notification.query.DispatchView;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** The ledger read side; newest first, cursor on the UUID v7 id. */
public class JooqDispatchReadRepository implements DispatchReadRepository {

    private final DSLContext dsl;

    public JooqDispatchReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<DispatchView> list(TenantId tenantId, String status, UUID before, int limit) {
        Condition where = EMAIL_DISPATCHES.TENANT_ID.eq(tenantId.value());
        if (status != null && !status.isBlank()) {
            where = where.and(EMAIL_DISPATCHES.STATUS.eq(status));
        }
        if (before != null) {
            where = where.and(EMAIL_DISPATCHES.ID.lt(before));
        }
        return dsl.select(
                        EMAIL_DISPATCHES.ID,
                        EMAIL_DISPATCHES.SOURCE_EVENT_ID,
                        EMAIL_DISPATCHES.NOTIFICATION_TYPE,
                        EMAIL_DISPATCHES.USER_ID,
                        EMAIL_DISPATCHES.RECIPIENT,
                        EMAIL_DISPATCHES.FROM_KEY,
                        EMAIL_DISPATCHES.TEMPLATE_KEY,
                        EMAIL_DISPATCHES.TEMPLATE_VERSION,
                        EMAIL_DISPATCHES.LOCALE,
                        EMAIL_DISPATCHES.SUBJECT,
                        EMAIL_DISPATCHES.STATUS,
                        EMAIL_DISPATCHES.REASON,
                        EMAIL_DISPATCHES.CREATED_AT,
                        EMAIL_DISPATCHES.PUBLISHED_AT)
                .from(EMAIL_DISPATCHES)
                .where(where)
                .orderBy(EMAIL_DISPATCHES.ID.desc())
                .limit(limit)
                .fetch(row -> new DispatchView(
                        row.value1(),
                        row.value2(),
                        row.value3(),
                        row.value4(),
                        row.value5(),
                        row.value6(),
                        row.value7(),
                        row.value8(),
                        row.value9(),
                        row.value10(),
                        row.value11(),
                        row.value12(),
                        row.value13(),
                        row.value14()));
    }
}
