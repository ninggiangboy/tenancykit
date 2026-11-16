package dev.ngb.billing.payment.application.incident.query;

import dev.ngb.billing.payment.domain.incident.IncidentKind;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort Stripe incidents on. Most recently seen first by default. */
public final class IncidentListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-last_seen_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.enumOf("kind", IncidentKind.class),
            FieldSpec.of("subject", FieldType.STRING),
            FieldSpec.of("occurrences", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("last_seen_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("resolved_at", FieldType.INSTANT).asNullable());

    private IncidentListing() {}
}
