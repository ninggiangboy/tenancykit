package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.EMAIL_DISPATCHES;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.notification.dispatch.application.notification.query.DispatchListing;
import dev.ngb.notification.dispatch.application.notification.query.DispatchReadRepository;
import dev.ngb.notification.dispatch.application.notification.query.DispatchView;
import dev.ngb.notification_jooq.tables.records.EmailDispatchesRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * The ledger read side; the table is row-level secured, so the scope only names the tenant and a leaked connection
 * cannot read across tenants.
 */
public class JooqDispatchReadRepository implements DispatchReadRepository {

    /** The fields an auditor may filter and sort on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(DispatchListing.SCHEMA)
            .column("id", EMAIL_DISPATCHES.ID)
            .column("source_event_id", EMAIL_DISPATCHES.SOURCE_EVENT_ID)
            .column("notification_type", EMAIL_DISPATCHES.NOTIFICATION_TYPE)
            .column("user_id", EMAIL_DISPATCHES.USER_ID)
            .column("recipient", EMAIL_DISPATCHES.RECIPIENT)
            .column("template_key", EMAIL_DISPATCHES.TEMPLATE_KEY)
            .column("status", EMAIL_DISPATCHES.STATUS)
            .column("reason", EMAIL_DISPATCHES.REASON)
            .column("created_at", EMAIL_DISPATCHES.CREATED_AT)
            .column("published_at", EMAIL_DISPATCHES.PUBLISHED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the ledger listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqDispatchReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<DispatchView> list(ListQuery query, TenantId tenantId) {
        Condition scope = EMAIL_DISPATCHES.TENANT_ID.eq(tenantId.value());
        return JooqLists.fetch(dsl, EMAIL_DISPATCHES, scope, BINDING, query, JooqDispatchReadRepository::toView);
    }

    private static DispatchView toView(EmailDispatchesRecord row) {
        return new DispatchView(
                row.getId(),
                row.getSourceEventId(),
                row.getNotificationType(),
                row.getUserId(),
                row.getRecipient(),
                row.getFromKey(),
                row.getTemplateKey(),
                row.getTemplateVersion(),
                row.getLocale(),
                row.getSubject(),
                row.getStatus(),
                row.getReason(),
                row.getCreatedAt(),
                row.getPublishedAt());
    }
}
