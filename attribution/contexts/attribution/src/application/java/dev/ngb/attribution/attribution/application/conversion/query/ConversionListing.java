package dev.ngb.attribution.attribution.application.conversion.query;

import dev.ngb.attribution.attribution.domain.conversion.ConversionSource;
import dev.ngb.attribution.attribution.domain.conversion.CreditStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/**
 * What a caller may filter and sort conversions on. The idempotency key, the visitor and the snapshotted touch chain are
 * internal: the first two belong to the write side, the third is read back as the chain itself.
 *
 * <p>{@code occurred_at} is the default order, newest first, because a report asks what happened lately; a window is
 * two filters, {@code filter[occurred_at][gte]} and {@code filter[occurred_at][lte]}, which replaces the old
 * {@code from}/{@code to} pair without losing it. {@code value} sorts so a caller can ask for the largest conversions,
 * and {@code id} makes any order total.
 */
public final class ConversionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-occurred_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("goal_id", FieldType.UUID),
            FieldSpec.of("click_id", FieldType.UUID).asNullable(),
            FieldSpec.of("value", FieldType.DECIMAL).asSortable(),
            FieldSpec.of("currency", FieldType.STRING).only(Op.EQ, Op.NEQ, Op.IN, Op.NIN),
            FieldSpec.of("occurred_at", FieldType.INSTANT).asSortable(),
            FieldSpec.enumOf("source", ConversionSource.class),
            FieldSpec.enumOf("credit_status", CreditStatus.class),
            FieldSpec.of("owning_campaign_id", FieldType.UUID).asNullable(),
            FieldSpec.of("computed_at", FieldType.INSTANT).asNullable());

    private ConversionListing() {}
}
