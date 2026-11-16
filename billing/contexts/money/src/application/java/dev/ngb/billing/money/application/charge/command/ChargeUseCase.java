package dev.ngb.billing.money.application.charge.command;

import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeDiscounts;
import dev.ngb.billing.money.api.charge.ChargeResult;
import dev.ngb.billing.money.api.charge.ChargeWallet;
import dev.ngb.billing.money.api.charge.DiscountQuote;
import dev.ngb.billing.money.api.charge.DiscountRequest;
import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import dev.ngb.billing.money.application.charge.port.ChargeRepository;
import dev.ngb.billing.money.application.ledger.port.LedgerRepository;
import dev.ngb.billing.money.application.messaging.service.MoneyActors;
import dev.ngb.billing.money.application.reservation.port.ReservationRepository;
import dev.ngb.billing.money.application.wallet.port.WalletRepository;
import dev.ngb.billing.money.domain.charge.Charge;
import dev.ngb.billing.money.domain.charge.ChargeService;
import dev.ngb.billing.money.domain.event.MoneyEvents;
import dev.ngb.billing.money.domain.reservation.Reservation;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import java.time.Instant;

/**
 * Records the actual cost incurred, from usage, from a reservation or on its own, once per idempotency key.
 * The charge, the ledger entry, the wallet projection, the reservation state, the discount consumption and the outbox
 * event commit in one transaction. A wallet that cannot pay yields a recorded {@code insufficient_funds} failure, not
 * an error.
 *
 * <p>The order inside the transaction is load-bearing: wallet lock, idempotency key, discount {@code claim}, charge
 * the net, add the charge with its lines, {@code consume}, publish. The key is read after the wallet lock, which is
 * what makes the read race-free under READ COMMITTED: a concurrent duplicate waits on that lock and then sees the
 * charge the first request wrote. Do not move it above the lock.
 *
 * <p>{@code consume} runs only for a succeeded charge. A failure therefore consumes no application and no budget, and
 * a replay consumes nothing either because it returns before the claim is asked.
 */
public class ChargeUseCase implements ChargeWallet {

    private final UnitOfWork unitOfWork;
    private final WalletRepository wallets;
    private final ReservationRepository reservations;
    private final ChargeRepository charges;
    private final LedgerRepository ledger;
    private final EventPublisher events;
    private final ChargeService chargeService;
    private final CustomerVisibility visibility;
    private final ChargeDiscounts discounts;
    private final IdGenerator ids;
    private final Clock clock;

    public ChargeUseCase(
            UnitOfWork unitOfWork,
            WalletRepository wallets,
            ReservationRepository reservations,
            ChargeRepository charges,
            LedgerRepository ledger,
            EventPublisher events,
            CustomerLookup customers,
            ChargeDiscounts discounts,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.wallets = wallets;
        this.reservations = reservations;
        this.charges = charges;
        this.ledger = ledger;
        this.events = events;
        this.chargeService = new ChargeService(ids);
        this.visibility = new CustomerVisibility(customers);
        this.discounts = discounts;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ChargeResult charge(ChargeCommand command) {
        Fingerprint.requireKey(command.idempotencyKey());
        String hash = Fingerprint.of(command.walletId(), command.reservationId(), command.amount(), command.usageRef(), command.reference());
        return unitOfWork.transactional(() -> {
            LedgerTarget target = ledgerTargetOf(command);
            Wallet wallet = wallets.lockById(target.walletId()).orElseThrow(() -> Messages.walletNotFound());
            visibility.requireVisible(wallet.customerId(), command.caller(), Messages::walletNotFound);

            var existing = charges.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                return replay(existing.get(), hash, command);
            }

            Reservation reservation = target.reservation();
            Instant now = clock.now();
            DiscountQuote quote = discounts.claim(new DiscountRequest(wallet.id(), command.amount(), command.discountContext(), now));
            ChargeService.Outcome outcome;
            try {
                outcome = chargeService.charge(new ChargeService.Request(
                        wallet, reservation, quote.net(), quote.discountTotal(), quote.lines(), command.source(), command.usageRef(),
                        command.reference(), new ReplayIdentity(command.idempotencyKey(), hash), MoneyActors.CHARGE, now));
            } catch (IllegalStateException reservationNotUsable) {
                throw new ConflictException(reservationNotUsable.getMessage());
            }

            charges.add(outcome.charge());
            if (outcome.charge().status() == dev.ngb.billing.money.api.charge.ChargeStatus.SUCCEEDED) {
                if (outcome.entry() != null) {
                    ledger.append(outcome.entry());
                }
                wallets.update(wallet);
                if (reservation != null) {
                    reservations.update(reservation);
                }
                discounts.consume(outcome.charge().id(), quote);
                events.publish(new MoneyEvents.ChargeSucceeded(ids.newId(), outcome.charge(), outcome.entry(), now));
            } else {
                events.publish(new MoneyEvents.ChargeFailed(ids.newId(), outcome.charge(), now));
            }
            return result(outcome.charge(), outcome.entry() == null ? null : outcome.entry().id(), false);
        });
    }

    /** The wallet the charge is against and, when it charges from a reservation, that reservation read once. */
    private record LedgerTarget(WalletId walletId, Reservation reservation) {}

    /**
     * Resolves the wallet before it is locked, so the lock order stays wallet then reservation and the reservation
     * is read once for the whole charge.
     */
    private LedgerTarget ledgerTargetOf(ChargeCommand command) {
        if (command.reservationId() == null) {
            return new LedgerTarget(command.walletId(), null);
        }
        Reservation reservation = reservations.findById(command.reservationId()).orElseThrow(() -> Messages.reservationNotFound());
        if (command.walletId() != null && !command.walletId().equals(reservation.walletId())) {
            throw new IllegalArgumentException("the reservation belongs to another wallet");
        }
        return new LedgerTarget(reservation.walletId(), reservation);
    }

    private ChargeResult replay(Charge original, String hash, ChargeCommand command) {
        Fingerprint.requireSameRequest("charge", command.idempotencyKey(), original.requestHash(), hash);
        var entry = ledger.findByCharge(original.id());
        return result(original, entry.map(e -> e.id()).orElse(null), true);
    }

    private static ChargeResult result(Charge charge, LedgerEntryId entryId, boolean replayed) {
        return new ChargeResult(charge.id(), charge.status(), charge.failureReason(), charge.amount(), entryId, replayed, charge.grossAmount(),
                charge.discounts());
    }
}
