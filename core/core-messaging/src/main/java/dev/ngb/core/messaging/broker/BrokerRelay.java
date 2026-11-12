package dev.ngb.core.messaging.broker;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.outbox.MessagingTables;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Backoff;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes committed outbox rows to RabbitMQ for the tenant whose scope is bound, and marks them only after the
 * broker confirms. The in-process relay keeps its own cursor, so this path never disturbs it.
 *
 * <p>At least once: a crash between the confirm and the mark republishes, and every consumer deduplicates by event
 * id through its inbox. A batch that cannot be published is rolled back and its rows are backed off; after the
 * attempt limit a row becomes {@code DEAD} and is kept for inspection instead of being retried forever.
 */
public class BrokerRelay {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerRelay.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};
    private static final long CONFIRM_TIMEOUT_MILLIS = 10_000;

    public record Result(int published, int failed, int dead) {}

    private record Row(UUID id, UUID tenantId, String aggregate, String type, String payload, Instant createdAt, int attempts) {}

    private static final class PublishFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<Row> rows;

        PublishFailure(List<Row> rows, RuntimeException cause) {
            super(cause);
            this.rows = rows;
        }
    }

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

    public BrokerRelay(
            UnitOfWork unitOfWork, DSLContext dsl, JsonMapper json, RabbitTemplate rabbit, Clock clock, MeterRegistry meters,
            MessagingProperties properties, String metricsPrefix) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.json = json;
        this.rabbit = rabbit;
        this.codec = new EnvelopeCodec(json);
        this.clock = clock;
        this.meters = meters;
        this.routes = new ExportRoutes(properties);
        this.metricsPrefix = metricsPrefix;
        this.batchSize = properties.relay().batchSize();
        this.maxAttempts = properties.relay().maxAttempts();
        this.backoff = new Backoff(properties.relay().backoffBase(), properties.relay().backoffCap(), 0.2);
    }

    /** Publishes up to one batch of due events for the bound tenant. */
    public Result relayDue() {
        if (routes.types().isEmpty()) {
            return new Result(0, 0, 0);
        }
        Instant now = clock.now();
        try {
            return unitOfWork.transactional(() -> publish(now));
        } catch (PublishFailure failure) {
            return unitOfWork.transactional(() -> recordFailure(failure.rows, (RuntimeException) failure.getCause(), now));
        }
    }

    private Result publish(Instant now) {
        List<Row> rows = claim(now);
        if (rows.isEmpty()) {
            return new Result(0, 0, 0);
        }
        try {
            rabbit.invoke(operations -> {
                for (Row row : rows) {
                    EventEnvelope event = toEnvelope(row);
                    operations.send(routes.exchangeFor(row.type()).orElseThrow(), row.type(), toMessage(event, codec.encode(event)), null);
                }
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
                return null;
            });
        } catch (RuntimeException failure) {
            throw new PublishFailure(rows, failure);
        }
        dsl.update(MessagingTables.OUTBOX)
                .set(MessagingTables.OUTBOX_PUBLISHED_AT, now.atOffset(ZoneOffset.UTC))
                .set(MessagingTables.OUTBOX_PUBLISH_STATUS, "PUBLISHED")
                .set(MessagingTables.OUTBOX_PUBLISH_ATTEMPTS, 0)
                .where(MessagingTables.OUTBOX_ID.in(rows.stream().map(Row::id).toList()))
                .execute();
        meters.counter(metricsPrefix + ".outbox.publish.delivered").increment(rows.size());
        return new Result(rows.size(), 0, 0);
    }

    private List<Row> claim(Instant now) {
        Condition due = MessagingTables.OUTBOX_PUBLISHED_AT.isNull()
                .and(MessagingTables.OUTBOX_PUBLISH_STATUS.eq("PENDING"))
                .and(MessagingTables.OUTBOX_PUBLISH_NEXT_AT.le(now.atOffset(ZoneOffset.UTC)))
                .and(MessagingTables.OUTBOX_TYPE.in(routes.types()));
        return dsl.select(
                        MessagingTables.OUTBOX_ID,
                        MessagingTables.OUTBOX_TENANT_ID,
                        MessagingTables.OUTBOX_AGGREGATE,
                        MessagingTables.OUTBOX_TYPE,
                        MessagingTables.OUTBOX_PAYLOAD,
                        MessagingTables.OUTBOX_CREATED_AT,
                        MessagingTables.OUTBOX_PUBLISH_ATTEMPTS)
                .from(MessagingTables.OUTBOX)
                .where(due)
                .orderBy(MessagingTables.OUTBOX_PUBLISH_NEXT_AT, MessagingTables.OUTBOX_ID)
                .limit(batchSize)
                .forUpdate()
                .skipLocked()
                .fetch(BrokerRelay::toRow);
    }

    private static Row toRow(Record record) {
        return new Row(
                record.get(MessagingTables.OUTBOX_ID),
                record.get(MessagingTables.OUTBOX_TENANT_ID),
                record.get(MessagingTables.OUTBOX_AGGREGATE),
                record.get(MessagingTables.OUTBOX_TYPE),
                record.get(MessagingTables.OUTBOX_PAYLOAD).data(),
                record.get(MessagingTables.OUTBOX_CREATED_AT).toInstant(),
                record.get(MessagingTables.OUTBOX_PUBLISH_ATTEMPTS));
    }

    private EventEnvelope toEnvelope(Row row) {
        Map<String, Object> payload = json.readValue(row.payload(), PAYLOAD);
        return new EventEnvelope(row.id(), new TenantId(row.tenantId()), row.type(), row.aggregate(), row.createdAt(), payload);
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

    private Result recordFailure(List<Row> rows, RuntimeException cause, Instant now) {
        int dead = 0;
        for (Row row : rows) {
            int attempts = row.attempts() + 1;
            boolean isDead = attempts >= maxAttempts;
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
                    .set(MessagingTables.OUTBOX_LAST_ERROR, reason(cause))
                    .where(MessagingTables.OUTBOX_ID.eq(row.id()))
                    .execute();
        }
        if (dead > 0) {
            meters.counter(metricsPrefix + ".outbox.publish.dead").increment(dead);
            LOG.error("{} outbox events are dead after {} publish attempts: {}", dead, maxAttempts, reason(cause));
        }
        return new Result(0, rows.size() - dead, dead);
    }

    private static String reason(RuntimeException cause) {
        String reason = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
