package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.EMAIL_DISPATCHES;

import dev.ngb.notification.dispatch.application.notification.port.EmailDispatchRepository;
import dev.ngb.notification.dispatch.domain.notification.EmailDispatch;
import org.jooq.DSLContext;

/** The dispatch ledger; the unique key makes a repeated transition a no-op, never a second email. */
public class JooqEmailDispatchRepository implements EmailDispatchRepository {

    private final DSLContext dsl;

    public JooqEmailDispatchRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insertIfAbsent(EmailDispatch dispatch) {
        return dsl.insertInto(EMAIL_DISPATCHES)
                        .set(EMAIL_DISPATCHES.ID, dispatch.id())
                        .set(EMAIL_DISPATCHES.TENANT_ID, dispatch.tenantId().value())
                        .set(EMAIL_DISPATCHES.SOURCE_EVENT_ID, dispatch.sourceEventId())
                        .set(EMAIL_DISPATCHES.NOTIFICATION_TYPE, dispatch.notificationType().name())
                        .set(EMAIL_DISPATCHES.USER_ID, dispatch.userId())
                        .set(EMAIL_DISPATCHES.RECIPIENT, dispatch.recipient())
                        .set(EMAIL_DISPATCHES.REQUEST_ID, dispatch.requestId())
                        .set(EMAIL_DISPATCHES.FROM_KEY, dispatch.fromKey())
                        .set(EMAIL_DISPATCHES.TEMPLATE_KEY, dispatch.templateKey())
                        .set(EMAIL_DISPATCHES.TEMPLATE_VERSION, dispatch.templateVersion())
                        .set(EMAIL_DISPATCHES.LOCALE, dispatch.locale())
                        .set(EMAIL_DISPATCHES.SUBJECT, dispatch.subject())
                        .set(EMAIL_DISPATCHES.STATUS, dispatch.status().name())
                        .set(EMAIL_DISPATCHES.REASON, dispatch.reason())
                        .set(EMAIL_DISPATCHES.CREATED_AT, dispatch.createdAt())
                        .set(EMAIL_DISPATCHES.PUBLISHED_AT, dispatch.publishedAt())
                        .onConflict(
                                EMAIL_DISPATCHES.TENANT_ID,
                                EMAIL_DISPATCHES.SOURCE_EVENT_ID,
                                EMAIL_DISPATCHES.NOTIFICATION_TYPE,
                                EMAIL_DISPATCHES.USER_ID)
                        .doNothing()
                        .execute()
                > 0;
    }
}
