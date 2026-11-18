package dev.ngb.platform.integration.application.tenantsystem.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemStatus;

/**
 * What a caller may filter and sort Tenant Systems on. {@code tenant_id} is a whitelisted field, not a separate
 * parameter: row-level security already narrows the listing to the caller's tenant, and a filter on another tenant
 * therefore returns no row rather than widening the read.
 *
 * <p>{@code name} offers {@code contains} because a system is found by what it is called; the column carries a trigram
 * index for it. Oldest first by default, as the parameterless listing always was.
 */
public final class TenantSystemListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("tenant_id", FieldType.UUID),
            FieldSpec.of("name", FieldType.STRING).asSortable().with(Op.CONTAINS),
            FieldSpec.enumOf("status", TenantSystemStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("version", FieldType.LONG));

    private TenantSystemListing() {}
}
