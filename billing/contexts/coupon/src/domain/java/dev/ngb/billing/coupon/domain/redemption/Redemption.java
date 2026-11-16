package dev.ngb.billing.coupon.domain.redemption;

import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * One coupon on one wallet for one run. It holds what is left of its own duration: how many applications it may
 * still take and how much it may still take off, and when its time runs out.
 *
 * <p>Consumption is the only change an ACTIVE redemption takes, and the counters only ever grow, so a redemption is
 * a record of what was actually given away. ENDED and REVOKED are final.
 */
public final class Redemption {

    private final RedemptionId id;
    private final TenantId tenantId;
    private final CouponId couponId;
    private final PromotionCodeId codeId;
    private final WalletId walletId;
    private final dev.ngb.billing.sharedkernel.id.CustomerId customerId;
    private final Integer maxApplications;
    private final Money maxTotalDiscount;
    private final Instant redeemedAt;
    private final Instant endsAt;
    private final String actor;
    private final ReplayIdentity replay;
    private final Currency currency;
    private RedemptionStatus status;
    private int applicationsUsed;
    private Money discountTotal;
    private EndReason endReason;
    private Instant endedAt;

    private Redemption(
            RedemptionId id,
            TenantId tenantId,
            CouponId couponId,
            PromotionCodeId codeId,
            WalletId walletId,
            dev.ngb.billing.sharedkernel.id.CustomerId customerId,
            Integer maxApplications,
            Money maxTotalDiscount,
            Instant redeemedAt,
            Instant endsAt,
            String actor,
            ReplayIdentity replay,
            Currency currency,
            RedemptionStatus status,
            int applicationsUsed,
            Money discountTotal,
            EndReason endReason,
            Instant endedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.couponId = Objects.requireNonNull(couponId, "couponId");
        this.codeId = codeId;
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.maxApplications = maxApplications;
        this.maxTotalDiscount = maxTotalDiscount;
        this.redeemedAt = Objects.requireNonNull(redeemedAt, "redeemedAt");
        this.endsAt = endsAt;
        this.actor = Objects.requireNonNull(actor, "actor");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = Objects.requireNonNull(status, "status");
        this.applicationsUsed = applicationsUsed;
        this.discountTotal = Objects.requireNonNull(discountTotal, "discountTotal");
        this.endReason = endReason;
        this.endedAt = endedAt;
        if (applicationsUsed < 0) {
            throw new IllegalArgumentException("applications used must not be negative");
        }
        if (maxApplications != null && maxApplications < 1) {
            throw new IllegalArgumentException("a redemption has at least one application");
        }
        if (maxTotalDiscount != null && !maxTotalDiscount.isPositive()) {
            throw new IllegalArgumentException("a total-discount limit must be positive");
        }
        if (!discountTotal.currency().equals(currency)) {
            throw new IllegalArgumentException("currency mismatch: redemption is %s, discount is %s".formatted(currency, discountTotal.currency()));
        }
        if (discountTotal.isNegative()) {
            throw new IllegalArgumentException("a discount total must not be negative");
        }
        if (endsAt != null && !endsAt.isAfter(redeemedAt)) {
            throw new IllegalArgumentException("a redemption ends after it starts");
        }
        if ((status == RedemptionStatus.ACTIVE) != (endedAt == null)) {
            throw new IllegalArgumentException("a finished redemption has the time it ended and an active one has none");
        }
        if (status == RedemptionStatus.ACTIVE && endReason != null) {
            throw new IllegalArgumentException("an active redemption has no end reason");
        }
    }

    public static Redemption activate(
            RedemptionId id,
            TenantId tenantId,
            CouponId couponId,
            PromotionCodeId codeId,
            WalletId walletId,
            dev.ngb.billing.sharedkernel.id.CustomerId customerId,
            Integer maxApplications,
            Money maxTotalDiscount,
            Instant endsAt,
            String actor,
            ReplayIdentity replay,
            Currency currency,
            Instant now) {
        return new Redemption(id, tenantId, couponId, codeId, walletId, customerId, maxApplications, maxTotalDiscount, now, endsAt, actor,
                replay, currency, RedemptionStatus.ACTIVE, 0, Money.zero(currency), null, null);
    }

    /**
     * A wallet-credit coupon hands its benefit over at redeem, so its redemption is finished from the start with one
     * application recorded.
     */
    public static Redemption granted(
            RedemptionId id,
            TenantId tenantId,
            CouponId couponId,
            PromotionCodeId codeId,
            WalletId walletId,
            dev.ngb.billing.sharedkernel.id.CustomerId customerId,
            String actor,
            ReplayIdentity replay,
            Currency currency,
            Instant now) {
        return new Redemption(id, tenantId, couponId, codeId, walletId, customerId, 1, null, now, null, actor, replay, currency,
                RedemptionStatus.ENDED, 1, Money.zero(currency), EndReason.GRANTED, now);
    }

    public static Redemption restore(
            RedemptionId id,
            TenantId tenantId,
            CouponId couponId,
            PromotionCodeId codeId,
            WalletId walletId,
            dev.ngb.billing.sharedkernel.id.CustomerId customerId,
            Integer maxApplications,
            Money maxTotalDiscount,
            Instant redeemedAt,
            Instant endsAt,
            String actor,
            ReplayIdentity replay,
            Currency currency,
            RedemptionStatus status,
            int applicationsUsed,
            Money discountTotal,
            EndReason endReason,
            Instant endedAt) {
        return new Redemption(id, tenantId, couponId, codeId, walletId, customerId, maxApplications, maxTotalDiscount, redeemedAt, endsAt, actor,
                replay, currency, status, applicationsUsed, discountTotal, endReason, endedAt);
    }

    /** Whether this redemption may still take something off a charge now. */
    public boolean isConsumable(Instant now) {
        return status == RedemptionStatus.ACTIVE
                && hasApplicationsLeft()
                && hasTotalDiscountLeft()
                && (endsAt == null || now.isBefore(endsAt));
    }

    public boolean hasApplicationsLeft() {
        return maxApplications == null || applicationsUsed < maxApplications;
    }

    public boolean hasTotalDiscountLeft() {
        return maxTotalDiscount == null || discountTotal.isLessThan(maxTotalDiscount);
    }

    /** What is left of the redemption's own total-discount cap; unbounded is {@code null}. */
    public Money remainingTotalDiscount() {
        return maxTotalDiscount == null ? null : maxTotalDiscount.minus(discountTotal);
    }

    /**
     * Records one application that took {@code taken} off a charge. When the application cap, the total-discount cap
     * or the end time has been reached, the redemption ends as EXHAUSTED in the same call.
     */
    public void consume(Money taken, Instant now) {
        Objects.requireNonNull(taken, "taken");
        Objects.requireNonNull(now, "now");
        if (status != RedemptionStatus.ACTIVE) {
            throw new IllegalStateException("the redemption is already " + status.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (isPastEnd(now)) {
            throw new IllegalStateException("the redemption has expired");
        }
        if (!taken.isPositive()) {
            throw new IllegalArgumentException("an application must take something off the charge");
        }
        if (!taken.currency().equals(currency)) {
            throw new IllegalArgumentException("currency mismatch: redemption is %s, discount is %s".formatted(currency, taken.currency()));
        }
        if (!hasApplicationsLeft()) {
            throw new IllegalStateException("the redemption has no applications left");
        }
        if (maxTotalDiscount != null && taken.isGreaterThan(remainingTotalDiscount())) {
            throw new IllegalArgumentException("the application exceeds the redemption's remaining total discount");
        }
        applicationsUsed++;
        discountTotal = discountTotal.plus(taken);
        if (!hasApplicationsLeft() || !hasTotalDiscountLeft() || isPastEnd(now)) {
            end(EndReason.EXHAUSTED, now);
        }
    }

    /** Ends the redemption and names why. A finished redemption is left as it is. */
    public void end(EndReason reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (status != RedemptionStatus.ACTIVE) {
            return;
        }
        status = reason == EndReason.REVOKED || reason == EndReason.COUPON_DEACTIVATED ? RedemptionStatus.REVOKED : RedemptionStatus.ENDED;
        endReason = reason;
        endedAt = now;
    }

    public boolean isPastEnd(Instant now) {
        return endsAt != null && !now.isBefore(endsAt);
    }

    public RedemptionId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public CouponId couponId() {
        return couponId;
    }

    public PromotionCodeId codeId() {
        return codeId;
    }

    public WalletId walletId() {
        return walletId;
    }

    public dev.ngb.billing.sharedkernel.id.CustomerId customerId() {
        return customerId;
    }

    public Integer maxApplications() {
        return maxApplications;
    }

    public Money maxTotalDiscount() {
        return maxTotalDiscount;
    }

    public Instant redeemedAt() {
        return redeemedAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public String actor() {
        return actor;
    }

    public ReplayIdentity replay() {
        return replay;
    }

    public String idempotencyKey() {
        return replay.idempotencyKey();
    }

    public String requestHash() {
        return replay.requestHash();
    }

    public Currency currency() {
        return currency;
    }

    public RedemptionStatus status() {
        return status;
    }

    public int applicationsUsed() {
        return applicationsUsed;
    }

    public Money discountTotal() {
        return discountTotal;
    }

    public EndReason endReason() {
        return endReason;
    }

    public Instant endedAt() {
        return endedAt;
    }
}
