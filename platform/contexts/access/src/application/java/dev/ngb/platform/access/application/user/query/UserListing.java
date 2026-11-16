package dev.ngb.platform.access.application.user.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.domain.user.UserStatus;

/**
 * What a caller may filter and sort users on. {@code tenant_id} is a whitelisted field, not a separate parameter: a
 * Tenant-scope caller is scoped to its own tenant by the read side, so naming another tenant narrows the listing to no
 * row instead of widening it, and a Platform administrator names the tenant it wants or reads them all.
 *
 * <p>{@code email} offers {@code contains} because "who is this address" is the question the admin screen is searched
 * with; the column carries a trigram index for it. The Keycloak subject is an internal identity and is never listed.
 * Ordered by email by default, as the parameterless listing always was.
 */
public final class UserListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "email",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("tenant_id", FieldType.UUID),
            FieldSpec.enumOf("scope", PermissionScope.class),
            FieldSpec.of("email", FieldType.STRING).asSortable().with(Op.CONTAINS),
            FieldSpec.of("display_name", FieldType.STRING),
            FieldSpec.enumOf("status", UserStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("version", FieldType.LONG));

    private UserListing() {}
}
