package dev.ngb.workflow.triggers.application.trigger.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort triggers on. Newest first by default. */
public final class TriggerListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING),
            FieldSpec.of("kind", FieldType.STRING),
            FieldSpec.of("source", FieldType.STRING),
            FieldSpec.of("event_type", FieldType.STRING).asNullable(),
            FieldSpec.of("enabled", FieldType.BOOLEAN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private TriggerListing() {}
}
