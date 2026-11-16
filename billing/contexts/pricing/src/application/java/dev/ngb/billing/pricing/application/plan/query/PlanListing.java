package dev.ngb.billing.pricing.application.plan.query;

import dev.ngb.billing.pricing.domain.plan.PlanStatus;
import dev.ngb.billing.pricing.domain.rule.PricingModel;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort pricing plans on. Newest first by default. */
public final class PlanListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("code", FieldType.STRING).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.enumOf("model", PricingModel.class),
            FieldSpec.of("currency", FieldType.STRING),
            FieldSpec.enumOf("status", PlanStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("retired_at", FieldType.INSTANT).asNullable());

    private PlanListing() {}
}
