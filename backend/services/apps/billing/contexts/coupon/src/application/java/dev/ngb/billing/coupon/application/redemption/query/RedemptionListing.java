package dev.ngb.billing.coupon.application.redemption.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort a wallet's redemptions on. Newest first by default. */
public final class RedemptionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-redeemed_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("coupon_id", FieldType.UUID),
            FieldSpec.of("code_id", FieldType.UUID).asNullable(),
            FieldSpec.of("wallet_id", FieldType.UUID),
            FieldSpec.of("customer_id", FieldType.UUID),
            FieldSpec.enumOf("status", "ACTIVE", "ENDED", "REVOKED"),
            FieldSpec.of("max_applications", FieldType.LONG).asNullable(),
            FieldSpec.of("applications_used", FieldType.LONG),
            FieldSpec.of("max_total_discount", FieldType.LONG).asNullable(),
            FieldSpec.of("discount_total", FieldType.LONG),
            FieldSpec.of("currency", FieldType.STRING),
            FieldSpec.of("redeemed_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("ends_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("end_reason", FieldType.STRING).asNullable(),
            FieldSpec.of("ended_at", FieldType.INSTANT).asNullable());

    private RedemptionListing() {}
}
