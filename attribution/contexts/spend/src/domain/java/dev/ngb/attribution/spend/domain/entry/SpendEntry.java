package dev.ngb.attribution.spend.domain.entry;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One advertising cost row: a campaign, a UTC day, a source, an amount in the campaign's currency and, when a provider
 * knows them, impressions and clicks.
 *
 * <p>The amount is never negative and the currency is the campaign's. There is no conversion between currencies in v1,
 * so a row in another currency is refused before it reaches the store.
 */
public record SpendEntry(
        UUID id,
        TenantId tenantId,
        UUID campaignId,
        LocalDate day,
        SpendSource source,
        UUID connectionId,
        BigDecimal amount,
        String currency,
        Long impressions,
        Long clicks,
        Instant updatedAt) {

    public SpendEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (amount.signum() < 0) {
            throw new BadRequestException("A cost amount is not negative.");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new BadRequestException("A cost entry needs an ISO 4217 currency.");
        }
        if (impressions != null && impressions < 0) {
            throw new BadRequestException("Impressions are not negative.");
        }
        if (clicks != null && clicks < 0) {
            throw new BadRequestException("Clicks are not negative.");
        }
    }

    /** A manual row: no provider connection, and the campaign's currency. */
    public static SpendEntry manual(
            UUID id,
            TenantId tenantId,
            UUID campaignId,
            LocalDate day,
            BigDecimal amount,
            String currency,
            Long impressions,
            Long clicks,
            Instant now) {
        return new SpendEntry(
                id, tenantId, campaignId, day, SpendSource.MANUAL, null, amount, currency, impressions, clicks, now);
    }
}
