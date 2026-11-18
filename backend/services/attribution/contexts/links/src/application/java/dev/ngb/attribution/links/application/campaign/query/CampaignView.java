package dev.ngb.attribution.links.application.campaign.query;

import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.CampaignStatus;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One campaign as the API shows it. */
public record CampaignView(
        UUID id,
        TenantId tenantId,
        String name,
        String slug,
        String channel,
        CampaignStatus status,
        AttributionModel model,
        int windowDays,
        String currency,
        LocalDate startsOn,
        LocalDate endsOn,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
