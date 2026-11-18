package dev.ngb.billing.coupon.application.coupon.query;

import java.time.Instant;
import java.util.UUID;

/** A promotion code as the management API shows it. The code text is the only thing a customer ever sees. */
public record PromotionCodeView(
        UUID id, String code, UUID couponId, Long maxRedemptions, long redeemedCount, Instant redeemBy, boolean active, Instant createdAt) {}
