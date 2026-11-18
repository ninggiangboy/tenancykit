package dev.ngb.billing.coupon.application.coupon.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort a coupon's promotion codes on. Oldest first by default. */
public final class PromotionCodeListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("coupon_id", FieldType.UUID),
            FieldSpec.of("code", FieldType.STRING).asSortable(),
            FieldSpec.of("max_redemptions", FieldType.LONG).asNullable(),
            FieldSpec.of("redeemed_count", FieldType.LONG),
            FieldSpec.of("redeem_by", FieldType.INSTANT).asNullable(),
            FieldSpec.of("active", FieldType.BOOLEAN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private PromotionCodeListing() {}
}
