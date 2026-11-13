package dev.ngb.billing.payment.application.payment.service;

import dev.ngb.billing.money.api.ledger.LedgerPostingResult;
import dev.ngb.billing.money.api.reservation.ReleaseReservation;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.api.wallet.CreditCommand;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.money.api.wallet.DebitCommand;
import dev.ngb.billing.money.api.wallet.DebitWallet;
import dev.ngb.billing.payment.application.messaging.service.PaymentActors;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.core.names.IdempotencyKeys;
import java.util.Objects;

/**
 * What the payment side tells the money context: credit a wallet for a settled top-up, debit it for a refund, and
 * give a refund's hold back. The reference, the reason and the idempotency key are the payment module's spelling,
 * so a retry is the same movement and never a second one.
 */
public class WalletMovements {

    private final CreditWallet credit;
    private final DebitWallet debit;
    private final ReleaseReservation release;

    public WalletMovements(CreditWallet credit, DebitWallet debit, ReleaseReservation release) {
        this.credit = Objects.requireNonNull(credit, "credit");
        this.debit = Objects.requireNonNull(debit, "debit");
        this.release = Objects.requireNonNull(release, "release");
    }

    public LedgerPostingResult credit(Payment payment) {
        return credit.credit(new CreditCommand(payment.walletId(), payment.amount(), "payment/" + payment.id(),
                "Stripe top-up " + payment.id(), IdempotencyKeys.topUp(payment.id()), PaymentActors.STRIPE));
    }

    public LedgerPostingResult debit(Payment payment, Refund refund) {
        return debit.debit(new DebitCommand(payment.walletId(), refund.amount(), "refund/" + refund.id(),
                "Stripe refund of payment " + payment.id(), IdempotencyKeys.refund(refund.id()), PaymentActors.STRIPE));
    }

    /** Gives the held balance back; a hold that already expired is reported by the money context, not thrown. */
    public void releaseHold(Refund refund) {
        if (refund.reservationId() != null) {
            release.release(new ReservationId(refund.reservationId()));
        }
    }
}
