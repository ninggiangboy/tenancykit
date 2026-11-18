package dev.ngb.attribution.spend.application.entry.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One cost row as the API shows it, with its source so a double entry is visible. */
public record SpendView(
        UUID id,
        UUID campaignId,
        LocalDate day,
        String source,
        UUID connectionId,
        BigDecimal amount,
        String currency,
        Long impressions,
        Long clicks,
        Instant updatedAt) {}
