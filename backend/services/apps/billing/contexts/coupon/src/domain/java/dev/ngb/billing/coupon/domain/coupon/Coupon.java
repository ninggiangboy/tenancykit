package dev.ngb.billing.coupon.domain.coupon;

import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * A promotion a Tenant offers its customers. Its terms are immutable: the only change a coupon ever takes is
 * deactivation, so a Charge recorded under it stays explainable for as long as the record exists. A bad coupon is
 * stopped by deactivating it, and optionally by revoking the redemptions already running.
 *
 * <p>The currency is the coupon's own and never changes; redeeming it onto a wallet in another currency is refused.
 */
public final class Coupon {

    private final CouponId id;
    private final TenantId tenantId;
    private final String name;
    private final Benefit benefit;
    private final Duration duration;
    private final Scope scope;
    private final Limits limits;
    private final Currency currency;
    private final boolean stackable;
    private final String createdBy;
    private final Instant createdAt;
    private CouponStatus status;
    private Instant deactivatedAt;

    private Coupon(
            CouponId id,
            TenantId tenantId,
            String name,
            Benefit benefit,
            Duration duration,
            Scope scope,
            Limits limits,
            Currency currency,
            boolean stackable,
            String createdBy,
            Instant createdAt,
            CouponStatus status,
            Instant deactivatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.name = requireText(name, "name");
        this.benefit = Objects.requireNonNull(benefit, "benefit");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.stackable = stackable;
        this.createdBy = requireText(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = Objects.requireNonNull(status, "status");
        this.deactivatedAt = deactivatedAt;
        if ((status == CouponStatus.INACTIVE) != (deactivatedAt != null)) {
            throw new IllegalArgumentException("a deactivated coupon has the time it was deactivated and an active one has none");
        }
        requireBenefitCurrency(currency);
        requireSameCurrency(duration.maxTotalDiscount(), currency);
        requireSameCurrency(limits.minCharge(), currency);
        requireSameCurrency(limits.maxDiscountPerApplication(), currency);
        requireSameCurrency(limits.budget(), currency);
    }

    public static Coupon create(
            CouponId id,
            TenantId tenantId,
            String name,
            Benefit benefit,
            Duration duration,
            Scope scope,
            Limits limits,
            Currency currency,
            boolean stackable,
            String createdBy,
            Instant now) {
        return new Coupon(id, tenantId, name, benefit, duration, scope, limits, currency, stackable, createdBy, now, CouponStatus.ACTIVE, null);
    }

    public static Coupon restore(
            CouponId id,
            TenantId tenantId,
            String name,
            Benefit benefit,
            Duration duration,
            Scope scope,
            Limits limits,
            Currency currency,
            boolean stackable,
            String createdBy,
            Instant createdAt,
            CouponStatus status,
            Instant deactivatedAt) {
        return new Coupon(id, tenantId, name, benefit, duration, scope, limits, currency, stackable, createdBy, createdAt, status, deactivatedAt);
    }

    /** Stops new redemptions. Idempotent: deactivating twice keeps the first time. */
    public void deactivate(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == CouponStatus.INACTIVE) {
            return;
        }
        status = CouponStatus.INACTIVE;
        deactivatedAt = now;
    }

    public boolean isActive() {
        return status == CouponStatus.ACTIVE;
    }

    /** Whether redemption is still open now, ignoring any redemption already running. */
    public boolean isOpenForRedemption(Instant now) {
        return isActive() && (limits.redeemBy() == null || now.isBefore(limits.redeemBy()));
    }

    public boolean isCredit() {
        return benefit instanceof Benefit.WalletCredit;
    }

    public CouponId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public Benefit benefit() {
        return benefit;
    }

    public Duration duration() {
        return duration;
    }

    public Scope scope() {
        return scope;
    }

    public Limits limits() {
        return limits;
    }

    public Currency currency() {
        return currency;
    }

    public boolean stackable() {
        return stackable;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CouponStatus status() {
        return status;
    }

    public Instant deactivatedAt() {
        return deactivatedAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private void requireBenefitCurrency(Currency couponCurrency) {
        Money amount = switch (benefit) {
            case Benefit.PercentOff ignored -> null;
            case Benefit.AmountOff amountOff -> amountOff.amount();
            case Benefit.WalletCredit credit -> credit.amount();
        };
        requireSameCurrency(amount, couponCurrency);
    }

    private static void requireSameCurrency(Money money, Currency currency) {
        if (money != null && !money.currency().equals(currency)) {
            throw new IllegalArgumentException("currency mismatch: coupon is %s, limit is %s".formatted(currency, money.currency()));
        }
    }
}
