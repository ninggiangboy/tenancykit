package dev.ngb.core.messaging.broker;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.outbox.MessagingTables;
import dev.ngb.core.messaging.outbox.OutboxRows;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Backoff;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes committed outbox rows to RabbitMQ for the tenant whose scope is bound, and marks them only after the
 * broker confirms. The in-process relay keeps its own cursor, so this path never disturbs it.
 *
 * <p>Publishing is a network call, so it never runs inside a database transaction: a short transaction leases the
 * row by pushing {@code publish_next_at} past the claim deadline, the broker is called outside any transaction, and
 * a second transaction marks the rows the broker confirmed. A broker that never confirms therefore holds no row
 * lock and does not block another worker; a crash between the claim and the mark leaves the row due again after the
 * deadline.
 *
 * <p>At least once: a crash between the confirm and the mark republishes, and every consumer deduplicates by event
 * id through its inbox. A batch that cannot be published is backed off; after the attempt limit a row becomes
 * {@code DEAD} and is kept for inspection instead of being retried forever.
 */
public class BrokerRelay {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerRelay.class);
    private static final long CONFIRM_TIMEOUT_MILLIS = 10_000;

    public record Result(int published, int failed, int dead) {}

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final JsonMapper json;
    private final RabbitTemplate rabbit;
    private final EnvelopeCodec codec;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ExportRoutes routes;
    private final String metricsPrefix;
    private final int batchSize;
    private final int maxAttempts;
    private final Backoff backoff;
    private final Duration claimDeadline;

    public BrokerRelay(
            UnitOfWork unitOfWork, DSLContext dsl, JsonMapper json, RabbitTemplate rabbit, Clock clock, MeterRegistry meters,
            MessagingProperties properties, String metricsPrefix) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.json = json;
        this.rabbit = rabbit;
        this.codec = new EnvelopeCodec(json, properties.document().schemaVersion());
        this.clock = clock;
        this.meters = meters;
        this.routes = new ExportRoutes(properties);
        this.metricsPrefix = metricsPrefix;
        this.batchSize = properties.relay().batchSize();
        this.maxAttempts = properties.relay().maxAttempts();
        this.backoff = new Backoff(properties.relay().backoffBase(), properties.relay().backoffCap(), properties.relay().jitter());
        this.claimDeadline = properties.relay().claimDeadline();
    }

    /** Publishes up to one batch of due events for the bound tenant. */
    public Result relayDue() {
        if (routes.types().isEmpty()) {
            return new Result(0, 0, 0);
        }
        Instant now = clock.now();
        List<OutboxRows.Row> claimed;
        try {
            claimed = unitOfWork.transactional(() -> claim(now));
        } catch (RuntimeException failure) {
            if (OutboxRows.contended(failure)) {
                // The lock wait timed out, so no row was leased and no attempt was spent: the next poll retries.
                LOG.warn("broker claim hit lock contention, retrying on the next poll");
                return new Result(0, 0, 0);
            }
            throw failure;
        }
        if (claimed.isEmpty()) {
            return new Result(0, 0, 0);
        }
        return publish(claimed, now);
    }

    /**
     * Leases a batch in its own short transaction. The rows are taken with {@code for update skip locked} and their
     * {@code publish_next_at} is pushed past the claim deadline, so the row is held by a deadline rather than by a
     * row lock: the transaction commits before the broker is called and another worker can take other rows at once.
     */
    private List<OutboxRows.Row> claim(Instant now) {
        Condition due = MessagingTables.OUTBOX_PUBLISHED_AT.isNull()
                .and(MessagingTables.OUTBOX_PUBLISH_STATUS.eq("PENDING"))
                .and(MessagingTables.OUTBOX_PUBLISH_NEXT_AT.le(now.atOffset(ZoneOffset.UTC)))
                .and(MessagingTables.OUTBOX_TYPE.in(routes.types()));
        List<OutboxRows.Row> rows =
                OutboxRows.claim(dsl, due, MessagingTables.OUTBOX_PUBLISH_NEXT_AT, batchSize, MessagingTables.OUTBOX_PUBLISH_ATTEMPTS);
        if (rows.isEmpty()) {
            return rows;
        }
        OffsetDateTime until = now.plus(claimDeadline).atOffset(ZoneOffset.UTC);
        dsl.update(MessagingTables.OUTBOX)
                .set(MessagingTables.OUTBOX_PUBLISH_NEXT_AT, until)
                .where(MessagingTables.OUTBOX_ID.in(rows.stream().map(OutboxRows.Row::id).toList()))
                .execute();
        return rows;
    }

    private Result publish(List<OutboxRows.Row> claimed, Instant now) {
        // A row that cannot be encoded can never be published; it is set aside so it cannot fail the healthy rows
        // claimed with it, and goes straight to DEAD.
        List<OutboxRows.Row> sendable = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        List<OutboxRows.Row> unencodable = new ArrayList<>();
        RuntimeException unencodableCause = null;
        for (OutboxRows.Row row : claimed) {
            try {
                EventEnvelope event = OutboxRows.toEnvelope(row, json);
                messages.add(toMessage(event, codec.encode(event)));
                sendable.add(row);
            } catch (RuntimeException failure) {
                unencodable.add(row);
                unencodableCause = failure;
            }
        }
        RuntimeException publishFailure = null;
        if (!sendable.isEmpty()) {
            try {
                rabbit.invoke(operations -> {
                    for (int i = 0; i < sendable.size(); i++) {
                        OutboxRows.Row row = sendable.get(i);
                        operations.send(routes.exchangeFor(row.type()).orElseThrow(), row.type(), messages.get(i), null);
                    }
                    operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
                    return null;
                });
            } catch (RuntimeException failure) {
                publishFailure = failure;
            }
        }
        RuntimeException failure = publishFailure;
        RuntimeException unencodableFailure = unencodableCause;
        return unitOfWork.transactional(() -> {
            Result broken = recordFailure(unencodable, unencodableFailure, now, true);
            if (failure != null) {
                Result unsent = recordFailure(sendable, failure, now, false);
                return new Result(0, unsent.failed() + broken.failed(), unsent.dead() + broken.dead());
            }
            return new Result(markPublished(sendable, now), broken.failed(), broken.dead());
        });
    }

    private int markPublished(List<OutboxRows.Row> rows, Instant now) {
        if (rows.isEmpty()) {
            return 0;
        }
        dsl.update(MessagingTables.OUTBOX)
                .set(MessagingTables.OUTBOX_PUBLISHED_AT, now.atOffset(ZoneOffset.UTC))
                .set(MessagingTables.OUTBOX_PUBLISH_STATUS, "PUBLISHED")
                .set(MessagingTables.OUTBOX_PUBLISH_ATTEMPTS, 0)
                .where(MessagingTables.OUTBOX_ID.in(rows.stream().map(OutboxRows.Row::id).toList()))
                .execute();
        OutboxRows.published(meters, metricsPrefix, rows.size());
        return rows.size();
    }

    private Message toMessage(EventEnvelope event, byte[] body) {
        return MessageBuilder.withBody(body)
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.eventId().toString())
                .setType(event.type())
                .setHeader("tenant-id", event.tenantId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    /** @param permanent the failure cannot succeed on a retry, so the row is dead at once */
    private Result recordFailure(List<OutboxRows.Row> rows, RuntimeException cause, Instant now, boolean permanent) {
        if (rows.isEmpty()) {
            return new Result(0, 0, 0);
        }
        if (!permanent && OutboxRows.contended(cause)) {
            // The lock wait timed out, so no attempt was spent: the row is made due again for the next poll.
            dsl.update(MessagingTables.OUTBOX)
                    .set(MessagingTables.OUTBOX_PUBLISH_NEXT_AT, now.atOffset(ZoneOffset.UTC))
                    .where(MessagingTables.OUTBOX_ID.in(rows.stream().map(OutboxRows.Row::id).toList()))
                    .execute();
            LOG.warn("broker publish of {} rows hit lock contention, retrying on the next poll", rows.size());
            return new Result(0, 0, 0);
        }
        String reason = OutboxRows.failureReason(cause);
        int dead = 0;
        for (OutboxRows.Row row : rows) {
            int attempts = row.attempts() + 1;
            boolean isDead = permanent || attempts >= maxAttempts;
            if (isDead) {
                dead++;
            }
            OffsetDateTime next = isDead
                    ? now.atOffset(ZoneOffset.UTC)
                    : now.plus(backoff.delayAfter(attempts, ThreadLocalRandom.current().nextDouble())).atOffset(ZoneOffset.UTC);
            dsl.update(MessagingTables.OUTBOX)
                    .set(MessagingTables.OUTBOX_PUBLISH_STATUS, isDead ? "DEAD" : "PENDING")
                    .set(MessagingTables.OUTBOX_PUBLISH_ATTEMPTS, attempts)
                    .set(MessagingTables.OUTBOX_PUBLISH_NEXT_AT, next)
                    .set(MessagingTables.OUTBOX_LAST_ERROR, reason)
                    .where(MessagingTables.OUTBOX_ID.eq(row.id()))
                    .execute();
        }
        if (dead > 0) {
            OutboxRows.publishDead(meters, metricsPrefix, dead);
            LOG.error("{} outbox events are dead after {} publish attempts: {}", dead, maxAttempts, reason);
        }
        return new Result(0, rows.size() - dead, dead);
    }
}
