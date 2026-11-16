package dev.ngb.attribution.links.application.link.query;

import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.LinkStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/**
 * What a caller may filter and sort links on. The password hash, the tenant and the click counter are internal and never
 * listed.
 *
 * <p>{@code campaign_id} is a filter rather than a required scope, so the same endpoint serves the tenant's whole link
 * list and one campaign's. {@code slug} offers {@code contains} because that is how a person finds a link they half
 * remember, and it carries a trigram index for it. Newest first by default: link ids are time-ordered, so {@code -id}.
 */
public final class LinkListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("campaign_id", FieldType.UUID).asNullable(),
            FieldSpec.of("slug", FieldType.STRING).with(Op.CONTAINS),
            FieldSpec.enumOf("kind", LinkKind.class),
            FieldSpec.of("external_ref", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("status", LinkStatus.class),
            FieldSpec.of("expires_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("max_clicks", FieldType.LONG).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private LinkListing() {}
}
