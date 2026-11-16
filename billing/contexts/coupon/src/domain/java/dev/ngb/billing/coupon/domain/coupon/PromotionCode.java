package dev.ngb.billing.coupon.domain.coupon;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A code a customer quotes to redeem a coupon. A coupon may have several, each with its own redemption limit and
 * closing time, so a campaign can hand out a batch of codes that share one benefit but stop at different points.
 *
 * <p>The code is stored upper-case and compared upper-case: a customer reads {@code SAVE20} off a poster and may type
 * it in any case, and the two must be the same code. Uniqueness is per tenant, not global, so two Tenants may both
 * own {@code SAVE20}.
 */
public final class PromotionCode {

    private final PromotionCodeId id;
    private final TenantId tenantId;
    private final CouponId couponId;
    private final String code;
    private final Long maxRedemptions;
    private final Instant redeemBy;
    private final String createdBy;
    private final Instant createdAt;
    private long redeemedCount;
    private boolean active;

    private PromotionCode(
            PromotionCodeId id,
            TenantId tenantId,
            CouponId couponId,
            String code,
            Long maxRedemptions,
            Instant redeemBy,
            String createdBy,
            Instant createdAt,
            long redeemedCount,
            boolean active) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.couponId = Objects.requireNonNull(couponId, "couponId");
        this.code = normalize(code);
        this.maxRedemptions = maxRedemptions;
        this.redeemBy = redeemBy;
        this.createdBy = requireText(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.redeemedCount = redeemedCount;
        this.active = active;
        if (maxRedemptions != null && maxRedemptions < 1) {
            throw new IllegalArgumentException("a code is redeemed at least once");
        }
        if (redeemedCount < 0) {
            throw new IllegalArgumentException("a code's redeemed count must not be negative");
        }
    }

    public static PromotionCode create(
            PromotionCodeId id, TenantId tenantId, CouponId couponId, String code, Long maxRedemptions, Instant redeemBy, String createdBy,
            Instant now) {
        return new PromotionCode(id, tenantId, couponId, code, maxRedemptions, redeemBy, createdBy, now, 0, true);
    }

    public static PromotionCode restore(
            PromotionCodeId id, TenantId tenantId, CouponId couponId, String code, Long maxRedemptions, Instant redeemBy, String createdBy,
            Instant createdAt, long redeemedCount, boolean active) {
        return new PromotionCode(id, tenantId, couponId, code, maxRedemptions, redeemBy, createdBy, createdAt, redeemedCount, active);
    }

    /** The exact text a code is stored and compared under. */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("a code must not be blank");
        }
        String trimmed = code.strip();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("a code is at most 64 characters");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /** Whether the code may start a new redemption now. */
    public boolean isOpen(Instant now) {
        return active && (redeemBy == null || now.isBefore(redeemBy)) && (maxRedemptions == null || redeemedCount < maxRedemptions);
    }

    public void deactivate() {
        active = false;
    }

    public PromotionCodeId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public CouponId couponId() {
        return couponId;
    }

    public String code() {
        return code;
    }

    public Long maxRedemptions() {
        return maxRedemptions;
    }

    public Instant redeemBy() {
        return redeemBy;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long redeemedCount() {
        return redeemedCount;
    }

    public boolean isActive() {
        return active;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
