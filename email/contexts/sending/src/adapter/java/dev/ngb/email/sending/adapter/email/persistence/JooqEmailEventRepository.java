package dev.ngb.email.sending.adapter.email.persistence;

import static dev.ngb.email_jooq.Tables.EMAIL_EVENTS;

import dev.ngb.email.sending.application.email.port.EmailEventRepository;
import dev.ngb.email.sending.domain.email.EmailEvent;
import dev.ngb.email.sending.domain.email.EmailEventType;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The append-only email history. */
public class JooqEmailEventRepository implements EmailEventRepository {

    private final DSLContext dsl;

    public JooqEmailEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void append(EmailEvent event) {
        dsl.insertInto(EMAIL_EVENTS)
                .set(EMAIL_EVENTS.ID, event.id())
                .set(EMAIL_EVENTS.TENANT_ID, event.tenantId().value())
                .set(EMAIL_EVENTS.EMAIL_ID, event.emailId())
                .set(EMAIL_EVENTS.CAMPAIGN_ID, event.campaignId())
                .set(EMAIL_EVENTS.TYPE, event.type().name())
                .set(EMAIL_EVENTS.LINK_ID, event.linkId())
                .set(EMAIL_EVENTS.OCCURRED_AT, event.occurredAt())
                .execute();
    }

    @Override
    public boolean exists(UUID emailId, EmailEventType type) {
        return dsl.fetchExists(DSL.selectOne()
                .from(EMAIL_EVENTS)
                .where(EMAIL_EVENTS.EMAIL_ID.eq(emailId))
                .and(EMAIL_EVENTS.TYPE.eq(type.name())));
    }

    @Override
    public long countSince(UUID emailId, EmailEventType type, java.time.Instant since) {
        return dsl.fetchCount(
                EMAIL_EVENTS,
                EMAIL_EVENTS.EMAIL_ID.eq(emailId)
                        .and(EMAIL_EVENTS.TYPE.eq(type.name()))
                        .and(EMAIL_EVENTS.OCCURRED_AT.ge(since)));
    }
}
