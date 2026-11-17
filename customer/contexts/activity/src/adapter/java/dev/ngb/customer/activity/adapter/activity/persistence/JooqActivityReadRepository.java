package dev.ngb.customer.activity.adapter.activity.persistence;

import static dev.ngb.customer_jooq.Tables.ACTIVITIES;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.query.Page;
import dev.ngb.core.query.PageInfo;
import dev.ngb.customer.activity.application.activity.service.ActivityPayloads;
import dev.ngb.customer.activity.application.activity.query.ActivityFilter;
import dev.ngb.customer.activity.application.activity.query.ActivityReadRepository;
import dev.ngb.customer.activity.application.activity.query.ActivityView;
import dev.ngb.customer_jooq.tables.records.ActivitiesRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * Selects activities straight into the view; row-level security narrows every statement to the current tenant.
 *
 * <p>A timeline is paged by a keyset of {@code (occurredAt, id)} rather than an offset, so a page stays correct while
 * newer activities are appended: the cursor names the last row served and the next page starts after it. It carries a
 * digest of the filter it was issued for, so a cursor from another filter is refused rather than silently applied.
 */
public class JooqActivityReadRepository implements ActivityReadRepository {

    private final DSLContext dsl;
    private final ActivityPayloads payloads;

    public JooqActivityReadRepository(DSLContext dsl, ActivityPayloads payloads) {
        this.dsl = dsl;
        this.payloads = payloads;
    }

    @Override
    public Page<ActivityView> findByCustomer(UUID customerId, ActivityFilter filter, int limit, String cursor) {
        Condition scope = ACTIVITIES.CUSTOMER_ID.eq(customerId);
        if (filter.type() != null) {
            scope = scope.and(ACTIVITIES.TYPE.eq(filter.type()));
        }
        if (filter.from() != null) {
            scope = scope.and(ACTIVITIES.OCCURRED_AT.ge(filter.from()));
        }
        if (filter.to() != null) {
            scope = scope.and(ACTIVITIES.OCCURRED_AT.le(filter.to()));
        }
        String shape = shape(filter);
        var query = dsl.selectFrom(ACTIVITIES).where(scope);
        if (cursor != null && !cursor.isBlank()) {
            Position position = decode(cursor, shape);
            query = query.and(ACTIVITIES.OCCURRED_AT.lt(position.occurredAt())
                    .or(ACTIVITIES.OCCURRED_AT.eq(position.occurredAt()).and(ACTIVITIES.ID.lt(position.id()))));
        }
        List<ActivitiesRecord> rows = query
                .orderBy(ACTIVITIES.OCCURRED_AT.desc(), ACTIVITIES.ID.desc())
                .limit(limit + 1)
                .fetch();
        boolean more = rows.size() > limit;
        List<ActivitiesRecord> page = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more && !page.isEmpty()) {
            ActivitiesRecord last = page.getLast();
            next = encode(last.getOccurredAt(), last.getId(), shape);
        }
        return new Page<>(page.stream().map(this::toView).toList(), new PageInfo(limit, null, null, next));
    }

    @Override
    public List<ActivityView> latest(UUID customerId, int limit) {
        return dsl.selectFrom(ACTIVITIES)
                .where(ACTIVITIES.CUSTOMER_ID.eq(customerId))
                .orderBy(ACTIVITIES.OCCURRED_AT.desc(), ACTIVITIES.ID.desc())
                .limit(limit)
                .fetch(this::toView);
    }

    private ActivityView toView(ActivitiesRecord row) {
        return new ActivityView(
                row.getId(),
                row.getCustomerId(),
                row.getType(),
                row.getOccurredAt(),
                row.getSource(),
                payloads.fromJson(row.getPayload() == null ? null : row.getPayload().data()),
                row.getActivityRef(),
                row.getCreatedAt());
    }

    /** A digest of what decides which rows a cursor runs over, so a cursor of another filter cannot be reused. */
    private static String shape(ActivityFilter filter) {
        return Fingerprint.of(
                        filter.type(), filter.from() == null ? null : filter.from().toString(),
                        filter.to() == null ? null : filter.to().toString())
                .substring(0, 12);
    }

    /** Where the previous page stopped: the activity's instant and its id, which together are a total order. */
    private record Position(Instant occurredAt, UUID id) {}

    private static String encode(Instant occurredAt, UUID id, String shape) {
        String text = occurredAt.toEpochMilli() + "|" + id + "|" + shape;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String token, String shape) {
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The cursor is not valid.");
        }
        String[] parts = text.split("\\|", -1);
        if (parts.length != 3 || !parts[2].equals(shape)) {
            throw new BadRequestException("The cursor is not valid for this filter.");
        }
        try {
            return new Position(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new BadRequestException("The cursor is not valid.");
        }
    }
}
