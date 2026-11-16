package dev.ngb.mailroom.sending.adapter.email.persistence;

import static dev.ngb.mailroom_jooq.Tables.EMAILS;
import static dev.ngb.mailroom_jooq.Tables.EMAIL_EVENTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.mailroom.sending.application.email.query.EmailEventView;
import dev.ngb.mailroom.sending.application.email.query.EmailListing;
import dev.ngb.mailroom.sending.application.email.query.EmailReadRepository;
import dev.ngb.mailroom.sending.application.email.query.EmailView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** The email listing and detail; the detail carries the append-only event history. */
public class JooqEmailReadRepository implements EmailReadRepository {

    /** The email fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(EmailListing.SCHEMA)
            .column("id", EMAILS.ID)
            .column("kind", EMAILS.KIND)
            .column("status", EMAILS.STATUS)
            .column("status_reason", EMAILS.STATUS_REASON)
            .column("to_address", EMAILS.TO_ADDRESS)
            .column("campaign_id", EMAILS.CAMPAIGN_ID)
            .column("receiver_id", EMAILS.RECEIVER_ID)
            .column("template_id", EMAILS.TEMPLATE_ID)
            .column("template_revision", EMAILS.TEMPLATE_REVISION)
            .column("from_key", EMAILS.FROM_KEY)
            .column("subject", EMAILS.SUBJECT)
            .column("body_purged_at", EMAILS.BODY_PURGED_AT)
            .column("delivery_updated_at", EMAILS.DELIVERY_UPDATED_AT)
            .column("created_at", EMAILS.CREATED_AT)
            .column("updated_at", EMAILS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the email listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqEmailReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<EmailView> list(ListQuery query) {
        return JooqLists.fetch(dsl, EMAILS, noCondition(), BINDING, query,
                row -> toView(row, List.of()));
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
            dev.ngb.mailroom_jooq.tables.records.EmailsRecord row, List<EmailEventView> events) {
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
