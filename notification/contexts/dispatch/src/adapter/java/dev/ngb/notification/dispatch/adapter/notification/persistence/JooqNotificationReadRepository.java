package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.query.NotificationReadRepository;
import dev.ngb.notification.dispatch.application.notification.query.NotificationView;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** The caller's inbox, newest first. The id is a UUID v7, so it is the cursor and the sort key at once. */
public class JooqNotificationReadRepository implements NotificationReadRepository {

    private final DSLContext dsl;

    public JooqNotificationReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<NotificationView> list(
            TenantId tenantId, UUID userId, boolean unreadOnly, UUID before, int limit) {
        Condition where = NOTIFICATIONS.TENANT_ID.eq(tenantId.value()).and(NOTIFICATIONS.USER_ID.eq(userId));
        if (unreadOnly) {
            where = where.and(NOTIFICATIONS.READ_AT.isNull());
        }
        if (before != null) {
            where = where.and(NOTIFICATIONS.ID.lt(before));
        }
        return dsl.select(
                        NOTIFICATIONS.ID,
                        NOTIFICATIONS.TYPE,
                        NOTIFICATIONS.TEMPLATE_KEY,
                        NOTIFICATIONS.TEMPLATE_VERSION,
                        NOTIFICATIONS.LOCALE,
                        NOTIFICATIONS.TITLE,
                        NOTIFICATIONS.BODY,
                        NOTIFICATIONS.LINK,
                        NOTIFICATIONS.READ_AT,
                        NOTIFICATIONS.CREATED_AT)
                .from(NOTIFICATIONS)
                .where(where)
                .orderBy(NOTIFICATIONS.ID.desc())
                .limit(limit)
                .fetch(row -> new NotificationView(
                        row.value1(),
                        row.value2(),
                        row.value3(),
                        row.value4(),
                        row.value5(),
                        row.value6(),
                        row.value7(),
                        row.value8(),
                        row.value9(),
                        row.value10()));
    }

    @Override
    public long unreadCount(TenantId tenantId, UUID userId) {
        return dsl.fetchCount(
                NOTIFICATIONS, NOTIFICATIONS.TENANT_ID.eq(tenantId.value())
                        .and(NOTIFICATIONS.USER_ID.eq(userId))
                        .and(NOTIFICATIONS.READ_AT.isNull()));
    }
}
