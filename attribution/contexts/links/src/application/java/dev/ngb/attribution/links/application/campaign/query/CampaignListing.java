package dev.ngb.attribution.links.application.campaign.query;

import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.CampaignStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/**
 * What a caller may filter and sort campaigns on. The tenant is never a field: it comes from the verified host and
 * row-level security applies it, so a listing cannot widen past it.
 *
 * <p>{@code id} is the tie-breaker and the default order: campaign ids are time-ordered, so newest first is {@code -id}.
 * The dates a campaign runs between are a window, not an instant, and the contract has no date type, so they are read
 * from the campaign itself rather than filtered.
 */
public final class CampaignListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.of("slug", FieldType.STRING),
            FieldSpec.of("channel", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("status", CampaignStatus.class),
            FieldSpec.enumOf("attribution_model", AttributionModel.class),
            FieldSpec.of("currency", FieldType.STRING).only(Op.EQ, Op.NEQ, Op.IN, Op.NIN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private CampaignListing() {}
}
