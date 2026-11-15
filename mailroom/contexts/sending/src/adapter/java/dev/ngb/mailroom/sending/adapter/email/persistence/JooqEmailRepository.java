package dev.ngb.mailroom.sending.adapter.email.persistence;

import static dev.ngb.mailroom_jooq.Tables.EMAILS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.sending.application.email.port.EmailRepository;
import dev.ngb.mailroom.sending.domain.email.Email;
import dev.ngb.mailroom.sending.domain.email.EmailStatus;
import dev.ngb.mailroom.shared.EmailKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The sent-email ledger. The insert and its outbox row share the caller's transaction. */
public class JooqEmailRepository implements EmailRepository {

    private final DSLContext dsl;

    public JooqEmailRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Email email) {
        dsl.insertInto(EMAILS)
                .set(EMAILS.ID, email.id())
                .set(EMAILS.TENANT_ID, email.tenantId().value())
                .set(EMAILS.KIND, email.kind().name())
                .set(EMAILS.CAMPAIGN_ID, email.campaignId())
                .set(EMAILS.RECEIVER_ID, email.receiverId())
                .set(EMAILS.TO_ADDRESS, email.toAddress())
                .set(EMAILS.FROM_KEY, email.fromKey())
                .set(EMAILS.FROM_NAME, email.fromName())
                .set(EMAILS.REPLY_TO, email.replyTo())
                .set(EMAILS.SUBJECT, email.subject())
                .set(EMAILS.TEMPLATE_ID, email.templateId())
                .set(EMAILS.TEMPLATE_REVISION, email.templateRevision())
                .set(EMAILS.STATUS, email.status().name())
                .set(EMAILS.STATUS_REASON, email.statusReason())
                .set(EMAILS.REQUEST_ID, email.requestId())
                .set(EMAILS.BODY_HTML, email.bodyHtml())
                .set(EMAILS.BODY_TEXT, email.bodyText())
                .set(EMAILS.BODY_PURGED_AT, email.bodyPurgedAt())
                .set(EMAILS.DELIVERY_UPDATED_AT, email.deliveryUpdatedAt())
                .set(EMAILS.CREATED_AT, email.createdAt())
                .set(EMAILS.UPDATED_AT, email.updatedAt())
                .execute();
    }

    @Override
    public Optional<Email> findById(UUID emailId) {
        return dsl.selectFrom(EMAILS).where(EMAILS.ID.eq(emailId)).fetchOptional(this::toEmail);
    }

    @Override
    public Optional<Email> findByRequestId(String requestId) {
        return dsl.selectFrom(EMAILS)
                .where(EMAILS.REQUEST_ID.eq(requestId))
                .fetchOptional(this::toEmail);
    }

    @Override
    public boolean applyDelivery(String requestId, EmailStatus status, String reason, Instant completedAt) {
        return dsl.update(EMAILS)
                        .set(EMAILS.STATUS, status.name())
                        .set(EMAILS.STATUS_REASON, reason)
                        .set(EMAILS.DELIVERY_UPDATED_AT, completedAt)
                        .set(EMAILS.UPDATED_AT, completedAt)
                        .where(EMAILS.REQUEST_ID.eq(requestId))
                        .and(EMAILS.DELIVERY_UPDATED_AT.isNull()
                                .or(EMAILS.DELIVERY_UPDATED_AT.lt(completedAt)))
                        .execute()
                == 1;
    }

    @Override
    public int purgeBodies(Instant olderThan, Instant now, int limit) {
        var ids = DSL.select(EMAILS.ID)
                .from(EMAILS)
                .where(EMAILS.CREATED_AT.lt(olderThan))
                .and(EMAILS.BODY_PURGED_AT.isNull())
                .limit(limit);
        return dsl.update(EMAILS)
                .set(EMAILS.BODY_HTML, (String) null)
                .set(EMAILS.BODY_TEXT, (String) null)
                .set(EMAILS.BODY_PURGED_AT, now)
                .where(EMAILS.ID.in(ids))
                .execute();
    }

    private Email toEmail(dev.ngb.mailroom_jooq.tables.records.EmailsRecord row) {
        return new Email(
                row.getId(),
                new TenantId(row.getTenantId()),
                EmailKind.valueOf(row.getKind()),
                row.getCampaignId(),
                row.getReceiverId(),
                row.getToAddress(),
                row.getFromKey(),
                row.getFromName(),
                row.getReplyTo(),
                row.getSubject(),
                row.getTemplateId(),
                row.getTemplateRevision(),
                EmailStatus.valueOf(row.getStatus()),
                row.getStatusReason(),
                row.getRequestId(),
                row.getBodyHtml(),
                row.getBodyText(),
                row.getBodyPurgedAt(),
                row.getDeliveryUpdatedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
