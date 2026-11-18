package dev.ngb.platform.access.application.role.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.core.security.PermissionScope;

/**
 * What a caller may filter and sort roles on. {@code tenant_id} is a whitelisted field, not a separate parameter: a
 * Tenant-scope caller is scoped to its own tenant by the read side, so naming another tenant narrows the listing to no
 * row instead of widening it, and a Platform administrator names the tenant it wants or reads them all.
 *
 * <p>{@code name} offers {@code contains} because a role is found by what it is called; the column carries a trigram
 * index for it. Ordered by name by default, as the parameterless listing always was.
 */
public final class RoleListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "name",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("tenant_id", FieldType.UUID),
            FieldSpec.enumOf("scope", PermissionScope.class),
            FieldSpec.of("name", FieldType.STRING).asSortable().with(Op.CONTAINS),
            FieldSpec.of("system", FieldType.BOOLEAN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("version", FieldType.LONG));

    private RoleListing() {}
}
