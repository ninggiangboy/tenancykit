package dev.ngb.mailer.email.adapter.delivery.persistence;

import static dev.ngb.mailer_jooq.Tables.EMAIL_BODIES;
import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERIES;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.email.application.email.port.EmailDeliveryRepository;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import dev.ngb.mailer.email.domain.delivery.EmailDelivery;
import dev.ngb.mailer.shared.FromKey;
import dev.ngb.mailer.shared.Recipient;
import dev.ngb.mailer_jooq.tables.records.EmailDeliveriesRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The accept side: one ledger row plus one body per recipient, and nothing when the row already exists. */
public class JooqEmailDeliveryRepository implements EmailDeliveryRepository {

    private final DSLContext dsl;

    public JooqEmailDeliveryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean addIfAbsent(EmailDelivery delivery, String replyTo, List<Recipient> cc, String html, String text) {
        // The unique key (tenant, request, recipient) is what makes a repeated command a no-op. The body is written
        // only when the ledger row was actually created, so a conflict cannot leave an orphan body.
        UUID inserted = dsl.insertInto(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.ID, delivery.id())
                .set(EMAIL_DELIVERIES.TENANT_ID, delivery.tenantId().value())
                .set(EMAIL_DELIVERIES.REQUEST_ID, delivery.requestId())
                .set(EMAIL_DELIVERIES.RECIPIENT, delivery.recipient().address())
                .set(EMAIL_DELIVERIES.FROM_KEY, delivery.fromKey().value())
                .set(EMAIL_DELIVERIES.SUBJECT, delivery.subject())
                .set(EMAIL_DELIVERIES.STATUS, delivery.status().name())
                .set(EMAIL_DELIVERIES.ATTEMPTS, delivery.attempts())
                .set(EMAIL_DELIVERIES.NEXT_ATTEMPT_AT, delivery.nextAttemptAt())
                .set(EMAIL_DELIVERIES.CREATED_AT, delivery.createdAt())
                .onConflict(EMAIL_DELIVERIES.TENANT_ID, EMAIL_DELIVERIES.REQUEST_ID, EMAIL_DELIVERIES.RECIPIENT)
                .doNothing()
                .returningResult(EMAIL_DELIVERIES.ID)
                .fetchOne(EMAIL_DELIVERIES.ID);
        if (inserted == null) {
            return false;
        }
        dsl.insertInto(EMAIL_BODIES)
                .set(EMAIL_BODIES.TENANT_ID, delivery.tenantId().value())
                .set(EMAIL_BODIES.DELIVERY_ID, delivery.id())
                .set(EMAIL_BODIES.SUBJECT, delivery.subject())
                .set(EMAIL_BODIES.REPLY_TO, replyTo)
                .set(EMAIL_BODIES.CC, cc.stream().map(Recipient::address).toArray(String[]::new))
                .set(EMAIL_BODIES.HTML_BODY, html)
                .set(EMAIL_BODIES.TEXT_BODY, text)
                .set(EMAIL_BODIES.CREATED_AT, delivery.createdAt())
                .execute();
        return true;
    }

    @Override
    public List<EmailDelivery> findExisting(TenantId tenantId, String requestId, Collection<String> recipients) {
        if (recipients.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.TENANT_ID.eq(tenantId.value()))
                .and(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .and(EMAIL_DELIVERIES.RECIPIENT.in(recipients))
                .fetch(JooqEmailDeliveryRepository::toDomain);
    }

    @Override
    public void resurrect(UUID id) {
        // Only a dead delivery is brought back. A live or successful one is left alone, which is what makes the
        // duplicate policy "no-op for live rows, resurrect only the dead ones".
        dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.STATUS, DeliveryStatus.PENDING.name())
                .set(EMAIL_DELIVERIES.ATTEMPTS, 0)
                .set(EMAIL_DELIVERIES.NEXT_ATTEMPT_AT, DSL.field("now()", Instant.class))
                .set(EMAIL_DELIVERIES.CLAIMED_UNTIL, (Instant) null)
                .set(EMAIL_DELIVERIES.LAST_ERROR, (String) null)
                .set(EMAIL_DELIVERIES.COMPLETED_AT, (Instant) null)
                .where(EMAIL_DELIVERIES.ID.eq(id))
                .and(EMAIL_DELIVERIES.STATUS.eq(DeliveryStatus.DEAD.name()))
                .execute();
    }

    static EmailDelivery toDomain(EmailDeliveriesRecord row) {
        return new EmailDelivery(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getRequestId(),
                new Recipient(row.getRecipient()),
                new FromKey(row.getFromKey()),
                row.getSubject(),
                DeliveryStatus.valueOf(row.getStatus()),
                row.getAttempts(),
                row.getNextAttemptAt(),
                row.getClaimedUntil(),
                row.getProviderMessageId(),
                row.getLastError(),
                row.getCreatedAt(),
                row.getCompletedAt());
    }
}
