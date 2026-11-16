package dev.ngb.billing.usage.application.usage.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/** What a caller may filter and sort usage events on. Newest occurrence first by default. */
public final class UsageEventListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-occurred_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("customer_id", FieldType.UUID),
            FieldSpec.of("metric", FieldType.STRING).with(Op.CONTAINS),
            FieldSpec.of("quantity", FieldType.DECIMAL),
            FieldSpec.of("unit", FieldType.STRING),
            FieldSpec.of("occurred_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("recorded_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("tenant_system_id", FieldType.UUID).asNullable(),
            FieldSpec.of("resource_ref", FieldType.STRING).asNullable(),
            FieldSpec.of("source_event_id", FieldType.STRING).asNullable());

    private UsageEventListing() {}
}
