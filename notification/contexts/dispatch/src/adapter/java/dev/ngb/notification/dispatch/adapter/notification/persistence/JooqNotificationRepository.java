package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATIONS;

import dev.ngb.notification.dispatch.application.notification.port.NotificationRepository;
import dev.ngb.notification.dispatch.domain.notification.Notification;
import org.jooq.DSLContext;

/** Writes the in-app row; the unique key turns a repeated transition into a no-op insert. */
public class JooqNotificationRepository implements NotificationRepository {

    private final DSLContext dsl;

    public JooqNotificationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insertIfAbsent(Notification notification) {
        return dsl.insertInto(NOTIFICATIONS)
                        .set(NOTIFICATIONS.ID, notification.id().value())
                        .set(NOTIFICATIONS.TENANT_ID, notification.tenantId().value())
                        .set(NOTIFICATIONS.USER_ID, notification.userId())
                        .set(NOTIFICATIONS.TYPE, notification.type().name())
                        .set(NOTIFICATIONS.SOURCE_EVENT_ID, notification.sourceEventId())
                        .set(NOTIFICATIONS.SOURCE_EVENT_TYPE, notification.sourceEventType())
                        .set(NOTIFICATIONS.TEMPLATE_KEY, notification.templateKey())
                        .set(NOTIFICATIONS.TEMPLATE_VERSION, notification.templateVersion())
                        .set(NOTIFICATIONS.LOCALE, notification.locale())
                        .set(NOTIFICATIONS.TITLE, notification.title())
                        .set(NOTIFICATIONS.BODY, notification.body())
                        .set(NOTIFICATIONS.LINK, notification.link())
                        .set(NOTIFICATIONS.CREATED_AT, notification.createdAt())
                        .onConflict(
                                NOTIFICATIONS.TENANT_ID,
                                NOTIFICATIONS.SOURCE_EVENT_ID,
                                NOTIFICATIONS.USER_ID,
                                NOTIFICATIONS.TYPE)
                        .doNothing()
                        .execute()
                > 0;
    }
}
