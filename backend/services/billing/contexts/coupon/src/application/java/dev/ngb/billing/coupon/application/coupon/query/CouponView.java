package dev.ngb.billing.coupon.application.coupon.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A coupon as the management API shows it. It is shaped for the caller and carries no domain type: the benefit is
 * flattened to the fields a client reads, so the read side never reaches into the aggregate.
 */
public record CouponView(
        UUID id,
        String name,
        String benefitType,
        Integer percentBasisPoints,
        Long amountOff,
        Long creditAmount,
        String currency,
        Instant redeemBy,
        Long maxRedemptions,
        Integer maxPerCustomer,
        long redemptionsCount,
        Long budget,
        long budgetUsed,
        boolean stackable,
        String status,
        String createdBy,
        Instant createdAt,
        Instant deactivatedAt,
        List<UUID> planIds) {}
