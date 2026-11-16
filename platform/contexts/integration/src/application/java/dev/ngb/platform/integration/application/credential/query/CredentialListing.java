package dev.ngb.platform.integration.application.credential.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.platform.integration.domain.credential.CredentialStatus;

/**
 * What a caller may filter and sort the credentials of one Tenant System on. Neither {@code tenant_id} nor
 * {@code tenant_system_id} is a field: the path fixes the system and the read side applies it as its scope, so another
 * system's credentials are not expressible. The secret and its digest are never listed.
 *
 * <p>{@code label} offers {@code contains} because a credential is found by the label it was issued with; the column
 * carries a trigram index for it. Oldest first by default, which puts the keys in the order they were issued.
 */
public final class CredentialListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("key_id", FieldType.STRING).asSortable(),
            FieldSpec.of("label", FieldType.STRING).with(Op.CONTAINS),
            FieldSpec.enumOf("status", CredentialStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("expires_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("revoked_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("version", FieldType.LONG));

    private CredentialListing() {}
}
