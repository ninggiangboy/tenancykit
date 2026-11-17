package dev.ngb.booking.webhook.application.delivery.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.booking.webhook.domain.delivery.DeliveryStatus;

/** What a caller may filter and sort webhook deliveries on. Newest first by default. */
public final class DeliveryListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("endpoint_id", FieldType.UUID),
            FieldSpec.of("event_id", FieldType.UUID),
            FieldSpec.of("event_type", FieldType.STRING),
            FieldSpec.enumOf("status", DeliveryStatus.class),
            FieldSpec.of("attempts", FieldType.LONG),
            FieldSpec.of("next_attempt_at", FieldType.INSTANT),
            FieldSpec.of("last_status_code", FieldType.LONG).asNullable(),
            FieldSpec.of("last_error", FieldType.STRING).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("completed_at", FieldType.INSTANT).asNullable());

    private DeliveryListing() {}
}
