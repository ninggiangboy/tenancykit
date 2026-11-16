package dev.ngb.attribution.attribution.application.goal.query;

import dev.ngb.attribution.attribution.domain.goal.GoalStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/**
 * What a caller may filter and sort goals on. A goal's key is the name a site or a backend reports, so it is the
 * default order: a reader scanning the list wants the keys in one predictable run, and {@code id} makes that order
 * total.
 *
 * <p>{@code default_value} and {@code currency} are nullable — a goal may have no default — so neither may be a sort
 * key, because a cursor cannot position itself on a null.
 */
public final class GoalListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "key",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("key", FieldType.STRING).asSortable(),
            FieldSpec.of("name", FieldType.STRING),
            FieldSpec.of("default_value", FieldType.DECIMAL).asNullable(),
            FieldSpec.of("currency", FieldType.STRING).only(Op.EQ, Op.NEQ, Op.IN, Op.NIN).asNullable(),
            FieldSpec.enumOf("status", GoalStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private GoalListing() {}
}
