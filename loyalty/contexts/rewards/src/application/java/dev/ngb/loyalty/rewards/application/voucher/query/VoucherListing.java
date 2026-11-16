package dev.ngb.loyalty.rewards.application.voucher.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;

/** What a caller may filter and sort a member's vouchers on. Newest first, which is how a wallet reads. */
public final class VoucherListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-issued_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("code", FieldType.STRING),
            FieldSpec.of("reward_id", FieldType.UUID),
            FieldSpec.enumOf("status", VoucherStatus.class),
            FieldSpec.of("issued_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("expires_at", FieldType.INSTANT),
            FieldSpec.of("used_at", FieldType.INSTANT).asNullable());

    private VoucherListing() {}
}
