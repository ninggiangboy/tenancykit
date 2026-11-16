package dev.ngb.billing.money.domain.charge;

import dev.ngb.core.names.IdempotencyKeys;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.domain.ledger.LedgerEntry;
import dev.ngb.billing.money.domain.reservation.Reservation;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Decides and applies a charge against a locked wallet: validates it, moves the wallet and builds the ledger entry in
 * one place, so the rules about balance, holds and the Postpaid limit cannot be bypassed.
 *
 * <p>It is given the net after any discounts. A net of zero is a legitimate success: nothing is posted, the wallet
 * does not move, and a reservation is captured at zero and returns its whole hold. A net the wallet cannot pay is a
 * recorded failure, and the caller adds no discount lines for it.
 */
public final class ChargeService {

    public static final String INSUFFICIENT_FUNDS = "insufficient_funds";

    /** The charge, and the ledger entry it produced (null when nothing was owed or the charge failed). */
    public record Outcome(Charge charge, LedgerEntry entry) {}

    /**
     * What the caller supplies for one charge. {@code amount} is the net, {@code discountTotal} what coupons took off
     * and {@code discounts} the lines explaining it. {@code reservation} is non-null only when charging from one.
     */
    public record Request(
            Wallet wallet, Reservation reservation, Money amount, Money discountTotal, List<DiscountLine> discounts, ChargeSource source,
            String usageRef, String reference, ReplayIdentity replay, String actor, Instant now) {
        public Request {
            Objects.requireNonNull(wallet, "wallet");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(discountTotal, "discountTotal");
            discounts = discounts == null ? List.of() : List.copyOf(discounts);
            Objects.requireNonNull(source, "source");
        }

        /** A charge with no coupons behind it: the net is the whole amount. */
        public Request(
                Wallet wallet, Reservation reservation, Money amount, ChargeSource source, String usageRef, String reference,
                ReplayIdentity replay, String actor, Instant now) {
            this(wallet, reservation, amount, Money.zero(amount.currency()), List.of(), source, usageRef, reference, replay, actor, now);
        }
    }

    private final IdGenerator ids;

    public ChargeService(IdGenerator ids) {
        this.ids = ids;
    }

    /**
     * @throws IllegalArgumentException for a negative amount or a currency that is not the wallet's
     * @throws IllegalStateException when the reservation cannot be captured
     */
    public Outcome charge(Request request) {
        Wallet wallet = request.wallet();
        Money amount = request.amount();
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a charge is never negative");
        }
        if (!amount.currency().equals(wallet.currency())) {
            throw new IllegalArgumentException("currency mismatch: wallet is %s, charge is %s".formatted(wallet.currency(), amount.currency()));
        }
        boolean fullyDiscounted = amount.isZero();
        if (fullyDiscounted && !request.discountTotal().isPositive()) {
            throw new IllegalArgumentException("a zero charge is only possible when a discount covered it");
        }

        ChargeId chargeId = new ChargeId(ids.newId());
        ReservationId reservationId = request.reservation() == null ? null : request.reservation().id();

        if (request.reservation() != null) {
            if (fullyDiscounted) {
                request.reservation().captureNothing(wallet, request.now());
            } else {
                request.reservation().capture(wallet, amount, request.now());
            }
        } else if (!fullyDiscounted && !wallet.canSpend(amount)) {
            Charge failed = Charge.failed(chargeId, wallet, amount, request.discountTotal(), request.source(), null, request.usageRef(),
                    request.reference(), request.replay(), INSUFFICIENT_FUNDS, request.now());
            return new Outcome(failed, null);
        }

        Charge succeeded = Charge.succeeded(chargeId, wallet, amount, request.discountTotal(), request.discounts(), request.source(),
                reservationId, request.usageRef(), request.reference(), request.replay(), request.now());
        if (fullyDiscounted) {
            return new Outcome(succeeded, null);
        }
        long sequence = wallet.post(amount.negate());
        LedgerEntry entry = LedgerEntry.debit(
                new LedgerEntryId(ids.newId()), wallet.tenantId(), wallet.id(), sequence, amount,
                new LedgerEntry.Source(request.reference() == null ? IdempotencyKeys.charge(chargeId) : request.reference(), reservationId, chargeId),
                new LedgerEntry.Origin(request.actor(), null, request.replay().idempotencyKey()), request.now());
        return new Outcome(succeeded, entry);
    }
}
