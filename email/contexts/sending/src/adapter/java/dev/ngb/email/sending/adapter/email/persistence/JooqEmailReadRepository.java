package dev.ngb.email.sending.adapter.email.persistence;

import static dev.ngb.email_jooq.Tables.EMAILS;
import static dev.ngb.email_jooq.Tables.EMAIL_EVENTS;

import dev.ngb.email.sending.application.email.query.EmailEventView;
import dev.ngb.email.sending.application.email.query.EmailReadRepository;
import dev.ngb.email.sending.application.email.query.EmailView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The email listing and detail; the detail carries the append-only event history. */
public class JooqEmailReadRepository implements EmailReadRepository {

    private final DSLContext dsl;

    public JooqEmailReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<EmailView> list(
            String status, String kind, UUID campaignId, String recipient, UUID afterId, int limit) {
        Condition where = DSL.noCondition();
        if (status != null && !status.isBlank()) {
            where = where.and(EMAILS.STATUS.eq(status));
        }
        if (kind != null && !kind.isBlank()) {
            where = where.and(EMAILS.KIND.eq(kind));
        }
        if (campaignId != null) {
            where = where.and(EMAILS.CAMPAIGN_ID.eq(campaignId));
        }
        if (recipient != null && !recipient.isBlank()) {
            where = where.and(EMAILS.TO_ADDRESS.likeIgnoreCase("%" + recipient + "%"));
        }
        if (afterId != null) {
            where = where.and(EMAILS.ID.lt(afterId));
        }
        return dsl.selectFrom(EMAILS)
                .where(where)
                .orderBy(EMAILS.ID.desc())
                .limit(limit)
                .fetch(row -> toView(row, List.of()));
    }

    @Override
    public Optional<EmailView> find(UUID emailId) {
        return dsl.selectFrom(EMAILS)
                .where(EMAILS.ID.eq(emailId))
                .fetchOptional(row -> toView(row, events(emailId)));
    }

    private List<EmailEventView> events(UUID emailId) {
        return dsl.select(
                        EMAIL_EVENTS.ID, EMAIL_EVENTS.TYPE, EMAIL_EVENTS.LINK_ID, EMAIL_EVENTS.OCCURRED_AT)
                .from(EMAIL_EVENTS)
                .where(EMAIL_EVENTS.EMAIL_ID.eq(emailId))
                .orderBy(EMAIL_EVENTS.OCCURRED_AT.asc())
                .fetch(row -> new EmailEventView(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    private static EmailView toView(
            dev.ngb.email_jooq.tables.records.EmailsRecord row, List<EmailEventView> events) {
        return new EmailView(
                row.getId(),
                row.getKind(),
                row.getStatus(),
                row.getStatusReason(),
                row.getToAddress(),
                row.getSubject(),
                row.getCampaignId(),
                row.getReceiverId(),
                row.getTemplateId(),
                row.getTemplateRevision(),
                row.getBodyHtml(),
                row.getBodyText(),
                row.getBodyPurgedAt() != null,
                row.getDeliveryUpdatedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                events);
    }
}
