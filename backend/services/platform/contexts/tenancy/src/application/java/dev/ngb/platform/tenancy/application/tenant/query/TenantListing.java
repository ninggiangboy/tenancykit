package dev.ngb.platform.tenancy.application.tenant.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.platform.tenancy.domain.tenant.TenantStatus;

/**
 * What a Platform administrator may filter and sort tenants on. The reserved {@code platform} tenant is not a field:
 * it is the caller's own scope and never appears in the listing, which the read side applies as its scope.
 *
 * <p>{@code name} offers {@code contains} because "which tenant is called that" is how the admin screen is searched; the
 * column carries a trigram index for it. Oldest first by default, which the time-ordered id already is.
 */
public final class TenantListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("code", FieldType.STRING).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable().with(Op.CONTAINS),
            FieldSpec.enumOf("status", TenantStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("version", FieldType.LONG));

    private TenantListing() {}
}
