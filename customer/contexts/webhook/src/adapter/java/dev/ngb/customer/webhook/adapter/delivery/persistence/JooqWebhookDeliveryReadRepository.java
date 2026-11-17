package dev.ngb.customer.webhook.adapter.delivery.persistence;

import static dev.ngb.customer_jooq.Tables.WEBHOOK_DELIVERIES;
import static dev.ngb.customer_jooq.Tables.WEBHOOK_DELIVERY_ATTEMPTS;
import static dev.ngb.customer_jooq.Tables.WEBHOOK_ENDPOINTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryAttemptView;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryReadRepository;
import dev.ngb.customer.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.customer.webhook.domain.delivery.DeliveryStatus;
import dev.ngb.customer_jooq.tables.records.WebhookDeliveriesRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects deliveries and attempts straight into the views; the domain aggregate is never built. */
public class JooqWebhookDeliveryReadRepository implements DeliveryReadRepository {

    /** The delivery fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(DeliveryListing.SCHEMA)
            .column("id", WEBHOOK_DELIVERIES.ID)
            .column("endpoint_id", WEBHOOK_DELIVERIES.ENDPOINT_ID)
            .column("event_id", WEBHOOK_DELIVERIES.EVENT_ID)
            .column("event_type", WEBHOOK_DELIVERIES.EVENT_TYPE)
            .column("status", WEBHOOK_DELIVERIES.STATUS)
            .column("attempts", WEBHOOK_DELIVERIES.ATTEMPTS)
            .column("next_attempt_at", WEBHOOK_DELIVERIES.NEXT_ATTEMPT_AT)
            .column("last_status_code", WEBHOOK_DELIVERIES.LAST_STATUS_CODE)
            .column("last_error", WEBHOOK_DELIVERIES.LAST_ERROR)
            .column("created_at", WEBHOOK_DELIVERIES.CREATED_AT)
            .column("completed_at", WEBHOOK_DELIVERIES.COMPLETED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the delivery listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqWebhookDeliveryReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<WebhookDeliveryView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, WEBHOOK_DELIVERIES, noCondition(), BINDING, query, JooqWebhookDeliveryReadRepository::toView);
    }

    @Override
    public Optional<WebhookDeliveryView> findById(UUID id) {
        return dsl.select(WEBHOOK_DELIVERIES.asterisk())
                .from(WEBHOOK_DELIVERIES)
                .join(WEBHOOK_ENDPOINTS)
                .on(WEBHOOK_ENDPOINTS.TENANT_ID.eq(WEBHOOK_DELIVERIES.TENANT_ID).and(WEBHOOK_ENDPOINTS.ID.eq(WEBHOOK_DELIVERIES.ENDPOINT_ID)))
                .where(WEBHOOK_DELIVERIES.ID.eq(id))
                .fetchOptional(row -> toView(row.into(WEBHOOK_DELIVERIES)));
    }

    @Override
    public List<DeliveryAttemptView> attempts(UUID deliveryId) {
        return dsl.select(WEBHOOK_DELIVERY_ATTEMPTS.ATTEMPT_NO, WEBHOOK_DELIVERY_ATTEMPTS.STATUS_CODE, WEBHOOK_DELIVERY_ATTEMPTS.ERROR,
                        WEBHOOK_DELIVERY_ATTEMPTS.RESPONSE_EXCERPT, WEBHOOK_DELIVERY_ATTEMPTS.LATENCY_MS, WEBHOOK_DELIVERY_ATTEMPTS.ATTEMPTED_AT)
                .from(WEBHOOK_DELIVERY_ATTEMPTS)
                .where(WEBHOOK_DELIVERY_ATTEMPTS.DELIVERY_ID.eq(deliveryId))
                .orderBy(WEBHOOK_DELIVERY_ATTEMPTS.ATTEMPT_NO)
                .fetch(row -> new DeliveryAttemptView(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5().longValue(), row.value6()));
    }

    private static WebhookDeliveryView toView(WebhookDeliveriesRecord row) {
        return new WebhookDeliveryView(
                row.getId(),
                row.getEndpointId(),
                row.getEventId(),
                row.getEventType(),
                row.getBody(),
                DeliveryStatus.valueOf(row.getStatus()),
                row.getAttempts(),
                row.getNextAttemptAt(),
                row.getLastStatusCode(),
                row.getLastError(),
                row.getCreatedAt(),
                row.getCompletedAt());
    }
}
