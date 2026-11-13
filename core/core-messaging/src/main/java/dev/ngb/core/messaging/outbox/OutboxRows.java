package dev.ngb.core.messaging.outbox;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.observability.MetricNames;
import dev.ngb.core.util.Causes;
import dev.ngb.core.util.Text;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox-row machinery both relays share: how a row is read, turned into an envelope, and reported when it
 * fails. Keeping it in one place means the in-process relay and the broker relay agree on how a contended row is
 * treated, on the clipped failure reason, and on the metric names.
 *
 * <p>The two relays each keep their own cursor on the same row, so {@code attempts} is the column the caller is
 * reading: {@code attempts} for the in-process relay, {@code publish_attempts} for the broker relay.
 */
public final class OutboxRows {

    /** The longest failure reason stored in the row's {@code last_error}. */
    public static final int MAX_REASON = 500;

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

    public record Row(UUID id, UUID tenantId, String aggregate, String type, String payload, Instant createdAt, int attempts) {}

    private OutboxRows() {}

    /**
     * Claims up to {@code limit} due rows with {@code for update skip locked}, so two workers never take the same
     * row.
     */
    public static List<Row> claim(DSLContext dsl, Condition due, Field<OffsetDateTime> orderBy, int limit, Field<Integer> attempts) {
        return dsl.select(
                        MessagingTables.OUTBOX_ID,
                        MessagingTables.OUTBOX_TENANT_ID,
                        MessagingTables.OUTBOX_AGGREGATE,
                        MessagingTables.OUTBOX_TYPE,
                        MessagingTables.OUTBOX_PAYLOAD,
                        MessagingTables.OUTBOX_CREATED_AT,
                        attempts)
                .from(MessagingTables.OUTBOX)
                .where(due)
                .orderBy(orderBy, MessagingTables.OUTBOX_ID)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch()
                .stream()
                .map(record -> toRow(record, attempts))
                .toList();
    }

    public static Row toRow(Record record, Field<Integer> attempts) {
        return new Row(
                record.get(MessagingTables.OUTBOX_ID),
                record.get(MessagingTables.OUTBOX_TENANT_ID),
                record.get(MessagingTables.OUTBOX_AGGREGATE),
                record.get(MessagingTables.OUTBOX_TYPE),
                record.get(MessagingTables.OUTBOX_PAYLOAD).data(),
                record.get(MessagingTables.OUTBOX_CREATED_AT).toInstant(),
                record.get(attempts));
    }

    public static EventEnvelope toEnvelope(Row row, JsonMapper json) {
        return new EventEnvelope(row.id(), new TenantId(row.tenantId()), row.type(), row.aggregate(),
                row.createdAt(), json.readValue(row.payload(), PAYLOAD));
    }

    /** True when the failure is a lock wait that timed out ({@code 55P03}) or a deadlock ({@code 40P01}). */
    public static boolean contended(Throwable failure) {
        return Causes.isRetryableContention(failure);
    }

    public static String failureReason(RuntimeException cause) {
        String reason = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return Text.clip(reason, MAX_REASON);
    }

    public static String clip(String text) {
        return Text.clip(text, MAX_REASON);
    }

    /** {@code <prefix>.outbox.delivered}: rows the in-process relay handed to the consumers. */
    public static void delivered(MeterRegistry meters, String prefix, int count) {
        if (count > 0) {
            meters.counter(MetricNames.in(prefix, MetricNames.OUTBOX_DELIVERED)).increment(count);
        }
    }

    /** {@code <prefix>.outbox.dead}: rows the in-process relay gave up on. */
    public static void dead(MeterRegistry meters, String prefix, int count) {
        if (count > 0) {
            meters.counter(MetricNames.in(prefix, MetricNames.OUTBOX_DEAD)).increment(count);
        }
    }

    /** {@code <prefix>.outbox.publish.delivered}: rows the broker relay confirmed. */
    public static void published(MeterRegistry meters, String prefix, int count) {
        if (count > 0) {
            meters.counter(MetricNames.in(prefix, MetricNames.OUTBOX_PUBLISH_DELIVERED)).increment(count);
        }
    }

    /** {@code <prefix>.outbox.publish.dead}: rows the broker relay gave up on. */
    public static void publishDead(MeterRegistry meters, String prefix, int count) {
        if (count > 0) {
            meters.counter(MetricNames.in(prefix, MetricNames.OUTBOX_PUBLISH_DEAD)).increment(count);
        }
    }
}
