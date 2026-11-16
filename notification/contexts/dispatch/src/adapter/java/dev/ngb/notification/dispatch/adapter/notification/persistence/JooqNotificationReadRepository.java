package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.notification.dispatch.application.notification.query.NotificationListing;
import dev.ngb.notification.dispatch.application.notification.query.NotificationReadRepository;
import dev.ngb.notification.dispatch.application.notification.query.NotificationView;
import dev.ngb.notification_jooq.tables.records.NotificationsRecord;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * The caller's inbox. The table is row-level secured, so the scope only names the user: the tenant is the transaction's
 * and a leaked connection cannot read across tenants.
 */
public class JooqNotificationReadRepository implements NotificationReadRepository {

    /** The fields a caller may filter and sort on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(NotificationListing.SCHEMA)
            .column("id", NOTIFICATIONS.ID)
            .column("type", NOTIFICATIONS.TYPE)
            .column("template_key", NOTIFICATIONS.TEMPLATE_KEY)
            .column("locale", NOTIFICATIONS.LOCALE)
            .column("title", NOTIFICATIONS.TITLE)
            .column("read_at", NOTIFICATIONS.READ_AT)
            .column("created_at", NOTIFICATIONS.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the inbox listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqNotificationReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<NotificationView> list(ListQuery query, TenantId tenantId, UUID userId) {
        Condition scope = NOTIFICATIONS.TENANT_ID.eq(tenantId.value()).and(NOTIFICATIONS.USER_ID.eq(userId));
        return JooqLists.fetch(dsl, NOTIFICATIONS, scope, BINDING, query, JooqNotificationReadRepository::toView);
    }

    private static NotificationView toView(NotificationsRecord row) {
        return new NotificationView(
                row.getId(),
                row.getType(),
                row.getTemplateKey(),
                row.getTemplateVersion(),
                row.getLocale(),
                row.getTitle(),
                row.getBody(),
                row.getLink(),
                row.getReadAt(),
                row.getCreatedAt());
    }
}
