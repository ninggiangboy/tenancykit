package dev.ngb.core.messaging.outbox;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Backoff;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves events from the transactional outbox to the in-process consumers, for the tenant whose scope is bound.
 *
 * <p>Each row is handled in its own transaction: it is claimed with {@code for update skip locked} (two relays
 * never take the same row), published as a Spring application event to every consumer, and marked delivered.
 * If a consumer throws, that transaction rolls back, so no half-done consumer work survives, and a second
 * transaction records the failure and schedules the retry with exponential backoff. After the attempt limit
 * the row becomes {@code DEAD} and is kept for inspection. Consumers deduplicate by event id (the inbox),
 * because a crash between a consumer's commit and the mark would otherwise deliver twice.
 */
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    public record Result(int delivered, int failed, int dead) {}

    private enum Outcome { NONE, DELIVERED, RETRY, DEAD, CONTENDED }

    private record Attempt(Outcome outcome, UUID rowId) {
        static final Attempt NONE = new Attempt(Outcome.NONE, null);
    }

    private static final class DispatchFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient OutboxRows.Row row;

        DispatchFailure(OutboxRows.Row row, RuntimeException cause) {
            super(cause);
            this.row = row;
        }
    }

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final JsonMapper json;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String metricsPrefix;
    private final int batchSize;
    private final int maxAttempts;
    private final Backoff backoff;

    public OutboxRelay(
            UnitOfWork unitOfWork, DSLContext dsl, JsonMapper json, ApplicationEventPublisher publisher, Clock clock, MeterRegistry meters,
            String metricsPrefix, int batchSize, int maxAttempts, Backoff backoff) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.json = json;
        this.publisher = publisher;
        this.clock = clock;
        this.meters = meters;
        this.metricsPrefix = metricsPrefix;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    /** Relays up to one batch of due events. */
    public Result relayDue() {
        int delivered = 0;
        int failed = 0;
        int dead = 0;
        Set<UUID> contended = new HashSet<>();
        for (int i = 0; i < batchSize; i++) {
            Attempt attempt = relayNext(contended);
            if (attempt.outcome() == Outcome.NONE) {
                break;
            }
            switch (attempt.outcome()) {
                case CONTENDED -> {
                    // The row stays due for the next poll, but passing over it here keeps the rows behind it moving
                    // instead of letting one busy row block the rest of the batch.
                    contended.add(attempt.rowId());
                }
                case DELIVERED -> delivered++;
                case RETRY -> failed++;
                case DEAD -> dead++;
                default -> throw new IllegalStateException("unexpected outcome " + attempt.outcome());
            }
        }
        OutboxRows.delivered(meters, metricsPrefix, delivered);
        OutboxRows.dead(meters, metricsPrefix, dead);
        return new Result(delivered, failed, dead);
    }

    private Attempt relayNext(Set<UUID> excluding) {
        Instant now = clock.now();
        try {
            return unitOfWork.transactional(() -> {
                Optional<OutboxRows.Row> claimed = claim(now, excluding);
                if (claimed.isEmpty()) {
                    return Attempt.NONE;
                }
                OutboxRows.Row row = claimed.get();
                try {
                    publisher.publishEvent(OutboxRows.toEnvelope(row, json));
                } catch (RuntimeException failure) {
                    throw new DispatchFailure(row, failure);
                }
                dsl.update(MessagingTables.OUTBOX)
                        .set(MessagingTables.OUTBOX_STATUS, "DELIVERED")
                        .set(MessagingTables.OUTBOX_LAST_ERROR, (String) null)
                        .where(MessagingTables.OUTBOX_ID.eq(row.id()))
                        .execute();
                return new Attempt(Outcome.DELIVERED, row.id());
            });
        } catch (DispatchFailure failure) {
            Outcome outcome = unitOfWork.transactional(() -> recordFailure(failure.row, (RuntimeException) failure.getCause(), now));
            return new Attempt(outcome, failure.row.id());
        }
    }

    private Optional<OutboxRows.Row> claim(Instant now, Set<UUID> excluding) {
        Condition due = MessagingTables.OUTBOX_STATUS.eq("PENDING")
                .and(MessagingTables.OUTBOX_NEXT_ATTEMPT_AT.le(now.atOffset(ZoneOffset.UTC)));
        if (!excluding.isEmpty()) {
            due = due.and(MessagingTables.OUTBOX_ID.notIn(excluding));
        }
        return OutboxRows.claim(dsl, due, MessagingTables.OUTBOX_NEXT_ATTEMPT_AT, 1, MessagingTables.OUTBOX_ATTEMPTS)
                .stream()
                .findFirst();
    }

    private Outcome recordFailure(OutboxRows.Row row, RuntimeException cause, Instant now) {
        if (OutboxRows.contended(cause)) {
            // The lock wait timed out, so no consumer committed and no attempt was spent: the row stays due and the
            // next poll tries it again.
            LOG.warn("outbox event {} ({}) hit lock contention, retrying on the next poll", row.id(), row.type());
            return Outcome.CONTENDED;
        }
        int failures = row.attempts() + 1;
        boolean dead = failures >= maxAttempts;
        Instant next = dead ? now : now.plus(backoff.delayAfter(failures, ThreadLocalRandom.current().nextDouble()));
        String reason = OutboxRows.failureReason(cause);
        dsl.update(MessagingTables.OUTBOX)
                .set(MessagingTables.OUTBOX_ATTEMPTS, failures)
                .set(MessagingTables.OUTBOX_STATUS, dead ? "DEAD" : "PENDING")
                .set(MessagingTables.OUTBOX_NEXT_ATTEMPT_AT, next.atOffset(ZoneOffset.UTC))
                .set(MessagingTables.OUTBOX_LAST_ERROR, reason)
                .where(MessagingTables.OUTBOX_ID.eq(row.id()))
                .execute();
        if (dead) {
            LOG.error("outbox event {} ({}) is dead after {} attempts: {}", row.id(), row.type(), failures, reason);
            return Outcome.DEAD;
        }
        LOG.warn("outbox event {} ({}) failed attempt {}, retrying at {}: {}", row.id(), row.type(), failures, next, reason);
        return Outcome.RETRY;
    }
}
