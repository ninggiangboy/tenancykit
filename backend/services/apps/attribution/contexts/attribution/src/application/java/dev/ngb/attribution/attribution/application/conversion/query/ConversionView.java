package dev.ngb.attribution.attribution.application.conversion.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One conversion as the API shows it. The chain and the credits are the two things a caller reads it for, so they are
 * carried as views rather than as the aggregate the engine works with.
 */
public record ConversionView(
        UUID id,
        UUID goalId,
        BigDecimal value,
        String currency,
        Instant occurredAt,
        String source,
        String creditStatus,
        UUID owningCampaignId,
        List<TouchView> touchChain,
        List<CreditView> credits) {

    public ConversionView {
        touchChain = touchChain == null ? List.of() : List.copyOf(touchChain);
        credits = credits == null ? List.of() : List.copyOf(credits);
    }

    /** One touchpoint as the API shows it. */
    public record TouchView(String clickId, String linkId, String campaignId, Instant at) {}

    /** One credit as the API shows it. */
    public record CreditView(String clickId, String credit) {}
}
