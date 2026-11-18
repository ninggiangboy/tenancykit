package dev.ngb.billing.money.domain.charge;

import dev.ngb.core.id.TenantId;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The actual cost incurred, and whether the wallet could pay it. A failed charge is kept as a record.
 *
 * <p>{@code amount} is the net the wallet paid. The gross it was worth is {@code amount + discountTotal}, and the
 * discount lines say which coupons took what off, in the order they applied. A fully discounted charge is a
 * succeeded Charge of 0 with a positive discount total: it has a record, so the application it consumed and a later
 * replay both point at something, but nothing was posted to the ledger.
 */
public final class Charge {

    private final ChargeId id;
    private final TenantId tenantId;
    private final WalletId walletId;
    private final Money amount;
    private final Money discountTotal;
    private final List<DiscountLine> discounts;
    private final ChargeSource source;
    private final ChargeStatus status;
    private final String failureReason;
    private final ReservationId reservationId;
    private final String usageRef;
    private final String reference;
    private final ReplayIdentity replay;
    private final Instant createdAt;

    private Charge(
            ChargeId id,
            TenantId tenantId,
            WalletId walletId,
            Money amount,
            Money discountTotal,
            List<DiscountLine> discounts,
            ChargeSource source,
            ChargeStatus status,
            String failureReason,
            ReservationId reservationId,
            String usageRef,
            String reference,
            ReplayIdentity replay,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.discountTotal = Objects.requireNonNull(discountTotal, "discountTotal");
        this.discounts = List.copyOf(discounts);
        this.source = Objects.requireNonNull(source, "source");
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.reservationId = reservationId;
        this.usageRef = usageRef;
        this.reference = reference;
        this.replay = Objects.requireNonNull(replay, "replay");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (amount.isNegative() || discountTotal.isNegative()) {
            throw new IllegalArgumentException("a charge and its discount are never negative");
        }
        if (!discountTotal.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("currency mismatch: charge is %s, discount is %s".formatted(amount.currency(), discountTotal.currency()));
        }
        if (amount.isZero() && !discountTotal.isPositive()) {
            throw new IllegalArgumentException("a charge must be positive unless a discount explains the zero");
        }
        if (status == ChargeStatus.FAILED && amount.isZero()) {
            throw new IllegalArgumentException("a failed charge records what was owed, which was positive");
        }
        Money lineTotal = Money.zero(amount.currency());
        for (DiscountLine line : discounts) {
            lineTotal = lineTotal.plus(line.amount());
        }
        // A failed charge keeps the gross it would have cost recoverable but consumed nothing, so it has no lines;
        // a succeeded charge's lines explain exactly the difference between its gross and its net.
        if (status == ChargeStatus.SUCCEEDED && !lineTotal.equals(discountTotal)) {
            throw new IllegalArgumentException("the discount lines must sum to the discount total");
        }
        if (status == ChargeStatus.FAILED && !discounts.isEmpty()) {
            throw new IllegalArgumentException("a failed charge consumed no coupon, so it has no discount lines");
        }
        if ((status == ChargeStatus.FAILED) != (failureReason != null)) {
            throw new IllegalArgumentException("a failed charge has a reason and a successful one has none");
        }
    }

    public static Charge succeeded(
            ChargeId id, Wallet wallet, Money net, Money discountTotal, List<DiscountLine> discounts, ChargeSource source,
            ReservationId reservationId, String usageRef, String reference, ReplayIdentity replay, Instant now) {
        return new Charge(id, wallet.tenantId(), wallet.id(), net, discountTotal, discounts, source, ChargeStatus.SUCCEEDED, null,
                reservationId, usageRef, reference, replay, now);
    }

    /** A failure records the net that was owed, with no discount consumed: nothing was given away on a charge that failed. */
    public static Charge failed(
            ChargeId id, Wallet wallet, Money net, Money grossedByCoupons, ChargeSource source, ReservationId reservationId, String usageRef,
            String reference, ReplayIdentity replay, String failureReason, Instant now) {
        return new Charge(id, wallet.tenantId(), wallet.id(), net, grossedByCoupons, List.of(), source, ChargeStatus.FAILED, failureReason,
                reservationId, usageRef, reference, replay, now);
    }

    public static Charge restore(
            ChargeId id, TenantId tenantId, WalletId walletId, Money amount, Money discountTotal, List<DiscountLine> discounts,
            ChargeSource source, ChargeStatus status, String failureReason, ReservationId reservationId, String usageRef, String reference,
            ReplayIdentity replay, Instant createdAt) {
        return new Charge(id, tenantId, walletId, amount, discountTotal, discounts, source, status, failureReason, reservationId, usageRef,
                reference, replay, createdAt);
    }

    public ChargeId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public WalletId walletId() {
        return walletId;
    }

    /** What the wallet paid. Zero only when a discount covered the whole charge. */
    public Money amount() {
        return amount;
    }

    public Money discountTotal() {
        return discountTotal;
    }

    /** What the charge was worth before coupons. */
    public Money grossAmount() {
        return amount.plus(discountTotal);
    }

    /** The coupons that took part, in the order they applied. */
    public List<DiscountLine> discounts() {
        return discounts;
    }

    public ChargeSource source() {
        return source;
    }

    public ChargeStatus status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public String usageRef() {
        return usageRef;
    }

    public String reference() {
        return reference;
    }

    /** The key and the digest of the request this charge was accepted under. */
    public ReplayIdentity replay() {
        return replay;
    }

    public String idempotencyKey() {
        return replay.idempotencyKey();
    }

    public String requestHash() {
        return replay.requestHash();
    }

    public Instant createdAt() {
        return createdAt;
    }
}
