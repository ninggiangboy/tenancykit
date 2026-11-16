package dev.ngb.loyalty.program.application.promotion.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;

/** What a caller may filter and sort promotions on. The catalog is highest priority first by default. */
public final class PromotionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-priority",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING),
            FieldSpec.enumOf("status", PromotionStatus.class),
            FieldSpec.of("priority", FieldType.LONG).asSortable(),
            FieldSpec.of("multiplier", FieldType.DECIMAL),
            FieldSpec.of("starts_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("ends_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private PromotionListing() {}
}
