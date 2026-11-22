package dev.ngb.billing.coupon.application.coupon.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort coupons on. Newest first by default. */
public final class CouponListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.of("benefit_type", FieldType.STRING),
            FieldSpec.of("currency", FieldType.STRING),
            FieldSpec.enumOf("status", "ACTIVE", "INACTIVE"),
            FieldSpec.of("stackable", FieldType.BOOLEAN),
            FieldSpec.of("redeem_by", FieldType.INSTANT).asNullable(),
            FieldSpec.of("redemptions_count", FieldType.LONG),
            FieldSpec.of("budget", FieldType.LONG).asNullable(),
            FieldSpec.of("budget_used", FieldType.LONG),
            FieldSpec.of("created_by", FieldType.STRING),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("deactivated_at", FieldType.INSTANT).asNullable());

    private CouponListing() {}
}
