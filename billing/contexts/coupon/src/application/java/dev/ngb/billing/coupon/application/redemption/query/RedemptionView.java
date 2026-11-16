package dev.ngb.billing.coupon.application.redemption.query;

import java.time.Instant;
import java.util.UUID;

/** A redemption as the API shows it: what is left of it, and how it ended when it has. Money is minor units. */
public record RedemptionView(
        UUID id,
        UUID couponId,
        String couponName,
        UUID codeId,
        UUID walletId,
        UUID customerId,
        String status,
        Integer maxApplications,
        int applicationsUsed,
        Long maxTotalDiscount,
        long discountTotal,
        String currency,
        Instant redeemedAt,
        Instant endsAt,
        String endReason,
        Instant endedAt) {}
