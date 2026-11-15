package dev.ngb.mailer.email.adapter.delivery.persistence;

import static dev.ngb.mailer_jooq.Tables.EMAIL_BODIES;
import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERIES;
import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERY_ATTEMPTS;

import dev.ngb.core.util.Text;
import dev.ngb.mailer.email.application.email.port.ClaimedDelivery;
import dev.ngb.mailer.email.application.email.port.DeliveryClaimRepository;
import dev.ngb.mailer.email.domain.delivery.DeliveryAttempt;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import dev.ngb.mailer.shared.Recipient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The delivery side: the claim, the compare-and-swap outcome, and the two bounded deletes housekeeping runs.
 *
 * <p>The claim is raw SQL because the shape it needs — select due with {@code skip locked}, then update the same rows
 * and return them — has no single jOOQ statement, and because {@code attempts = attempts + 1} has to happen in the
 * claim itself.
 */
public class JooqDeliveryClaimRepository implements DeliveryClaimRepository {

    private static final int MAX_ERROR = 500;

    private final DSLContext dsl;

    public JooqDeliveryClaimRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ClaimedDelivery> claimDue(Instant now, int limit, Duration lease) {
        // skip locked: a row another worker holds is invisible here, so two workers never take the same delivery.
        // attempts moves here, not in the outcome: a process that dies after the provider accepted the message but
        // before the outcome commit would otherwise lose the increment and re-send forever.
        List<UUID> claimed = dsl.fetch("""
                        with due as (
                            select d.id from email_deliveries d
                            where d.status in ('PENDING', 'IN_PROGRESS')
                              and (d.next_attempt_at is null or d.next_attempt_at <= ?)
                              and (d.claimed_until is null or d.claimed_until < ?)
                            order by d.next_attempt_at, d.id
                            limit ?
                            for update of d skip locked
                        )
                        update email_deliveries d
                        set status = 'IN_PROGRESS', attempts = d.attempts + 1, claimed_until = ?
                        from due where d.id = due.id
                        returning d.id
                        """, Timestamp.from(now), Timestamp.from(now), limit, Timestamp.from(now.plus(lease)))
                .getValues(EMAIL_DELIVERIES.ID);
        if (claimed.isEmpty()) {
            return List.of();
        }
        // A body that was purged between the claim and the send must not leave the delivery claimed forever: the left
        // join yields a null body, and the sender reports that as a transient failure rather than skipping the row.
        return dsl.select(
                        EMAIL_DELIVERIES.asterisk(), EMAIL_BODIES.REPLY_TO, EMAIL_BODIES.CC,
                        EMAIL_BODIES.HTML_BODY, EMAIL_BODIES.TEXT_BODY)
                .from(EMAIL_DELIVERIES)
                .leftJoin(EMAIL_BODIES)
                .on(EMAIL_BODIES.TENANT_ID.eq(EMAIL_DELIVERIES.TENANT_ID)
                        .and(EMAIL_BODIES.DELIVERY_ID.eq(EMAIL_DELIVERIES.ID)))
                .where(EMAIL_DELIVERIES.ID.in(claimed))
                .orderBy(EMAIL_DELIVERIES.ID)
                .fetch(row -> {
                    String[] cc = row.get(EMAIL_BODIES.CC);
                    List<Recipient> recipients = cc == null
                            ? List.of()
                            : Arrays.stream(cc).map(Recipient::of).toList();
                    return new ClaimedDelivery(
                            JooqEmailDeliveryRepository.toDomain(row.into(EMAIL_DELIVERIES)),
                            row.get(EMAIL_BODIES.REPLY_TO),
                            recipients,
                            row.get(EMAIL_BODIES.HTML_BODY),
                            row.get(EMAIL_BODIES.TEXT_BODY));
                });
    }

    @Override
    public int recordOutcome(
            UUID deliveryId,
            int claimedAttempts,
            DeliveryAttempt attempt,
            DeliveryStatus status,
            String providerMessageId,
            Instant nextAttemptAt,
            Instant completedAt) {
        dsl.insertInto(
                        EMAIL_DELIVERY_ATTEMPTS,
                        EMAIL_DELIVERY_ATTEMPTS.ID,
                        EMAIL_DELIVERY_ATTEMPTS.TENANT_ID,
                        EMAIL_DELIVERY_ATTEMPTS.DELIVERY_ID,
                        EMAIL_DELIVERY_ATTEMPTS.ATTEMPT_NO,
                        EMAIL_DELIVERY_ATTEMPTS.OUTCOME,
                        EMAIL_DELIVERY_ATTEMPTS.ERROR,
                        EMAIL_DELIVERY_ATTEMPTS.ATTEMPTED_AT)
                .select(DSL.select(
                                DSL.field("gen_random_uuid()", UUID.class),
                                EMAIL_DELIVERIES.TENANT_ID,
                                EMAIL_DELIVERIES.ID,
                                DSL.val(attempt.attemptNo(), EMAIL_DELIVERY_ATTEMPTS.ATTEMPT_NO),
                                DSL.val(attempt.outcome().name(), EMAIL_DELIVERY_ATTEMPTS.OUTCOME),
                                DSL.val(Text.clip(attempt.error(), MAX_ERROR), EMAIL_DELIVERY_ATTEMPTS.ERROR),
                                DSL.val(attempt.attemptedAt(), EMAIL_DELIVERY_ATTEMPTS.ATTEMPTED_AT))
                        .from(EMAIL_DELIVERIES)
                        .where(EMAIL_DELIVERIES.ID.eq(deliveryId)))
                .onConflict(EMAIL_DELIVERY_ATTEMPTS.DELIVERY_ID, EMAIL_DELIVERY_ATTEMPTS.ATTEMPT_NO)
                .doNothing()
                .execute();
        // The CAS matches the attempt count the claim wrote and the state the claim left. A row another worker has
        // taken over changes neither, so its newer owner's result stands and this update touches nothing.
        return dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.STATUS, status.name())
                .set(EMAIL_DELIVERIES.NEXT_ATTEMPT_AT, nextAttemptAt)
                .set(EMAIL_DELIVERIES.CLAIMED_UNTIL, (Instant) null)
                .set(EMAIL_DELIVERIES.PROVIDER_MESSAGE_ID, providerMessageId)
                .set(EMAIL_DELIVERIES.LAST_ERROR, Text.clip(attempt.error(), MAX_ERROR))
                .set(EMAIL_DELIVERIES.COMPLETED_AT, completedAt)
                .where(EMAIL_DELIVERIES.ID.eq(deliveryId))
                .and(EMAIL_DELIVERIES.ATTEMPTS.eq(claimedAttempts))
                .and(EMAIL_DELIVERIES.STATUS.eq(DeliveryStatus.IN_PROGRESS.name()))
                .execute();
    }

    @Override
    public int killExhausted(Instant now, int maxAttempts) {
        // A worker that died during its final attempt leaves the row IN_PROGRESS past its lease. Without this it is
        // claimed again for ever; with it the retry loop is finite.
        return dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.STATUS, DeliveryStatus.DEAD.name())
                .set(EMAIL_DELIVERIES.COMPLETED_AT, now)
                .set(EMAIL_DELIVERIES.LAST_ERROR,
                        DSL.coalesce(
                                EMAIL_DELIVERIES.LAST_ERROR,
                                DSL.val("the worker was lost during the final attempt")))
                .where(EMAIL_DELIVERIES.STATUS.eq(DeliveryStatus.IN_PROGRESS.name()))
                .and(EMAIL_DELIVERIES.CLAIMED_UNTIL.le(now))
                .and(EMAIL_DELIVERIES.ATTEMPTS.ge(maxAttempts))
                .execute();
    }

    @Override
    public int purgeBodies(Instant olderThan, int limit) {
        return dsl.execute("""
                        delete from email_bodies where ctid in (
                            select ctid from email_bodies where created_at < ? order by created_at limit ?
                        )
                        """, Timestamp.from(olderThan), limit);
    }

    @Override
    public int purgeInbox(Instant olderThan, int limit) {
        return dsl.execute("""
                        delete from event_inbox where ctid in (
                            select ctid from event_inbox where processed_at < ? order by processed_at limit ?
                        )
                        """, Timestamp.from(olderThan), limit);
    }
}
