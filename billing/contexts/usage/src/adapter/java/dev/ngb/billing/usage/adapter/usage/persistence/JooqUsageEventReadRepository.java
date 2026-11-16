package dev.ngb.billing.usage.adapter.usage.persistence;

import static dev.ngb.billing_jooq.Tables.USAGE_EVENTS;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.sum;

import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.usage.application.usage.query.UsageEventListing;
import dev.ngb.billing.usage.application.usage.query.UsageEventReadRepository;
import dev.ngb.billing.usage.application.usage.query.UsageEventView;
import dev.ngb.billing.usage.application.usage.query.UsageRecordView;
import dev.ngb.billing.usage.application.usage.query.UsageTotalView;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects usage straight into the read models; it never builds the aggregate. */
public class JooqUsageEventReadRepository implements UsageEventReadRepository {

    /** The usage fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(UsageEventListing.SCHEMA)
            .column("id", USAGE_EVENTS.ID)
            .column("customer_id", USAGE_EVENTS.CUSTOMER_ID)
            .column("metric", USAGE_EVENTS.METRIC)
            .column("quantity", USAGE_EVENTS.QUANTITY)
            .column("unit", USAGE_EVENTS.UNIT)
            .column("occurred_at", USAGE_EVENTS.OCCURRED_AT)
            .column("recorded_at", USAGE_EVENTS.RECORDED_AT)
            .column("tenant_system_id", USAGE_EVENTS.TENANT_SYSTEM_ID)
            .column("resource_ref", USAGE_EVENTS.RESOURCE_REF)
            .column("source_event_id", USAGE_EVENTS.SOURCE_EVENT_ID)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the usage listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqUsageEventReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<UsageEventView> page(ListQuery query, List<UUID> customerIds) {
        if (customerIds != null && customerIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = customerIds == null ? noCondition() : USAGE_EVENTS.CUSTOMER_ID.in(customerIds);
        return JooqLists.fetch(dsl, USAGE_EVENTS, scope, BINDING, query, JooqUsageEventReadRepository::toView);
    }

    private static UsageEventView toView(dev.ngb.billing_jooq.tables.records.UsageEventsRecord row) {
        return new UsageEventView(
                row.getId(),
                row.getCustomerId(),
                row.getMetric(),
                row.getQuantity(),
                row.getUnit(),
                row.getOccurredAt(),
                row.getRecordedAt(),
                row.getIdempotencyKey(),
                row.getTenantSystemId(),
                row.getResourceRef(),
                row.getSourceEventId());
    }

    @Override
    public List<UsageRecordView> find(CustomerId customerId, Set<String> metrics, Instant from, Instant to, int limit) {
        Condition filter = USAGE_EVENTS.CUSTOMER_ID.eq(customerId.value())
                .and(USAGE_EVENTS.OCCURRED_AT.ge(from))
                .and(USAGE_EVENTS.OCCURRED_AT.lt(to))
                .and(metrics.isEmpty() ? noCondition() : USAGE_EVENTS.METRIC.in(metrics));
        return dsl.select(USAGE_EVENTS.ID, USAGE_EVENTS.CUSTOMER_ID, USAGE_EVENTS.METRIC, USAGE_EVENTS.UNIT, USAGE_EVENTS.QUANTITY,
                        USAGE_EVENTS.OCCURRED_AT)
                .from(USAGE_EVENTS)
                .where(filter)
                .orderBy(USAGE_EVENTS.OCCURRED_AT, USAGE_EVENTS.ID)
                .limit(limit)
                .fetch(row -> new UsageRecordView(row.value1(), row.value2(), row.value3(), row.value4(), row.value5(), row.value6()));
    }

    @Override
    public List<UsageTotalView> totals(CustomerId customerId, Set<String> metrics, Instant from, Instant to) {
        Condition filter = USAGE_EVENTS.CUSTOMER_ID.eq(customerId.value())
                .and(USAGE_EVENTS.OCCURRED_AT.ge(from))
                .and(USAGE_EVENTS.OCCURRED_AT.lt(to))
                .and(metrics.isEmpty() ? noCondition() : USAGE_EVENTS.METRIC.in(metrics));
        return dsl.select(USAGE_EVENTS.METRIC, USAGE_EVENTS.UNIT, sum(USAGE_EVENTS.QUANTITY), count())
                .from(USAGE_EVENTS)
                .where(filter)
                .groupBy(USAGE_EVENTS.METRIC, USAGE_EVENTS.UNIT)
                .orderBy(USAGE_EVENTS.METRIC)
                .fetch(row -> new UsageTotalView(row.value1(), row.value2(), row.value3(), row.value4().longValue()));
    }
}
