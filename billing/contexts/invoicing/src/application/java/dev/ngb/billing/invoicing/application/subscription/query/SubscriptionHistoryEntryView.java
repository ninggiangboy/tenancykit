package dev.ngb.billing.invoicing.application.subscription.query;

import dev.ngb.billing.invoicing.domain.subscription.SubscriptionHistoryEntry;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A subscription history line. {@code amount} is the period price and {@code paidAmount} is what the wallet paid after
 * coupons; a row written before coupons existed has no paid amount, so a reader falls back to the price.
 */
public record SubscriptionHistoryEntryView(
        UUID id, SubscriptionHistoryEntry.Type type, Integer periodNumber, Instant periodStart, Instant periodEnd, Money amount, Money paidAmount,
        UUID chargeId, String detail, Instant occurredAt) {}
