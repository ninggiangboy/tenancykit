package dev.ngb.billing.payment.adapter.stripe.persistence;

import static dev.ngb.billing_jooq.Tables.STRIPE_EVENTS;
import static dev.ngb.billing_jooq.Tables.STRIPE_EVENT_ATTEMPTS;

import dev.ngb.billing.payment.application.stripe.query.EventAttemptView;
import dev.ngb.billing.payment.application.stripe.query.StripeEventListing;
import dev.ngb.billing.payment.application.stripe.query.StripeEventReadRepository;
import dev.ngb.billing.payment.application.stripe.query.StripeEventView;
import dev.ngb.billing.payment.domain.stripe.EventStatus;
import dev.ngb.billing_jooq.tables.records.StripeEventsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects Stripe events and their attempt history straight into views. */
public class JooqStripeEventReadRepository implements StripeEventReadRepository {

    /** The event fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(StripeEventListing.SCHEMA)
            .column("id", STRIPE_EVENTS.ID)
            .column("event_id", STRIPE_EVENTS.EVENT_ID)
            .column("type", STRIPE_EVENTS.TYPE)
            .column("status", STRIPE_EVENTS.STATUS)
            .column("attempts", STRIPE_EVENTS.ATTEMPTS)
            .column("next_attempt_at", STRIPE_EVENTS.NEXT_ATTEMPT_AT)
            .column("last_error", STRIPE_EVENTS.LAST_ERROR)
            .column("received_at", STRIPE_EVENTS.RECEIVED_AT)
            .column("processed_at", STRIPE_EVENTS.PROCESSED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the event listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqStripeEventReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<StripeEventView> list(ListQuery query) {
        return JooqLists.fetch(dsl, STRIPE_EVENTS, org.jooq.impl.DSL.noCondition(), BINDING, query,
                JooqStripeEventReadRepository::toView);
    }

    @Override
    public Optional<StripeEventView> findById(UUID id) {
        return dsl.selectFrom(STRIPE_EVENTS)
                .where(STRIPE_EVENTS.ID.eq(id))
                .fetchOptional(JooqStripeEventReadRepository::toView);
    }

    @Override
    public List<EventAttemptView> attempts(UUID eventId) {
        return dsl.select(STRIPE_EVENT_ATTEMPTS.ATTEMPT_NO, STRIPE_EVENT_ATTEMPTS.OUTCOME, STRIPE_EVENT_ATTEMPTS.DETAIL,
                        STRIPE_EVENT_ATTEMPTS.ATTEMPTED_AT)
                .from(STRIPE_EVENT_ATTEMPTS)
                .where(STRIPE_EVENT_ATTEMPTS.EVENT_ROW_ID.eq(eventId))
                .orderBy(STRIPE_EVENT_ATTEMPTS.ATTEMPT_NO)
                .fetch(row -> new EventAttemptView(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    private static StripeEventView toView(StripeEventsRecord row) {
        return new StripeEventView(
                row.getId(),
                row.getEventId(),
                row.getType(),
                EventStatus.valueOf(row.getStatus()),
                row.getAttempts(),
                row.getNextAttemptAt(),
                row.getLastError(),
                row.getReceivedAt(),
                row.getProcessedAt());
    }
}
