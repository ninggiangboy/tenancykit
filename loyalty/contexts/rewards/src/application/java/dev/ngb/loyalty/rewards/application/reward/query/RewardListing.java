package dev.ngb.loyalty.rewards.application.reward.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;

/** What a caller may filter and sort the catalog on. The cheapest reward first by default, then by name. */
public final class RewardListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "points_cost",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.of("points_cost", FieldType.LONG).asSortable(),
            FieldSpec.of("stock", FieldType.LONG).asNullable(),
            FieldSpec.enumOf("status", RewardStatus.class),
            FieldSpec.of("valid_from", FieldType.INSTANT).asNullable(),
            FieldSpec.of("valid_to", FieldType.INSTANT).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private RewardListing() {}
}
