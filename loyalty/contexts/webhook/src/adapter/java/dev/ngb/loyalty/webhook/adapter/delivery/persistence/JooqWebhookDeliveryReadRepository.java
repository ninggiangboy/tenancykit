package dev.ngb.loyalty.webhook.adapter.delivery.persistence;

import static dev.ngb.loyalty_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryReadRepository;
import dev.ngb.loyalty.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.loyalty.webhook.domain.delivery.DeliveryStatus;
import dev.ngb.loyalty_jooq.tables.records.WebhookDeliveriesRecord;
import org.jooq.DSLContext;

/** Selects deliveries straight into the view; the domain aggregate is never built. */
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
