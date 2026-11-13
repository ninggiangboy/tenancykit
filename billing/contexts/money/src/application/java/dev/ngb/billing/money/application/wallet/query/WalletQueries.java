package dev.ngb.billing.money.application.wallet.query;

import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.application.charge.query.ChargeReadRepository;
import dev.ngb.billing.money.application.charge.query.ChargeView;
import dev.ngb.billing.money.application.ledger.query.LedgerEntryView;
import dev.ngb.billing.money.application.ledger.query.LedgerReadRepository;
import dev.ngb.billing.money.application.reservation.query.ReservationReadRepository;
import dev.ngb.billing.money.application.reservation.query.ReservationView;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Read side of the money module, within the current tenant. */
public class WalletQueries {


    private final UnitOfWork unitOfWork;
    private final WalletReadRepository wallets;
    private final LedgerReadRepository ledger;
    private final ReservationReadRepository reservations;
    private final ChargeReadRepository charges;
    private final CustomerVisibility visibility;

    public WalletQueries(
            UnitOfWork unitOfWork, WalletReadRepository wallets, LedgerReadRepository ledger, ReservationReadRepository reservations,
            ChargeReadRepository charges, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.wallets = wallets;
        this.ledger = ledger;
        this.reservations = reservations;
        this.charges = charges;
        this.visibility = new CustomerVisibility(customers);
    }

    public WalletView wallet(WalletId id) {
        return wallet(id, null);
    }

    public WalletView wallet(WalletId id, UUID caller) {
        return unitOfWork.transactional(() -> {
            WalletView wallet = wallets.findById(id).orElseThrow(() -> Messages.walletNotFound());
            visibility.requireVisible(wallet.customerId(), caller, Messages::walletNotFound);
            return wallet;
        });
    }

    public List<WalletView> walletsOf(CustomerId customerId) {
        return walletsOf(customerId, null);
    }

    public List<WalletView> walletsOf(CustomerId customerId, UUID caller) {
        return unitOfWork.transactional(() -> {
            visibility.requireVisible(customerId, caller, Messages::customerNotFound);
            return wallets.listByCustomer(customerId);
        });
    }

    /** Entries after {@code afterSequence}, oldest first, at most {@link PageSize#MAX}. */
    public List<LedgerEntryView> ledger(WalletId walletId, long afterSequence, int limit) {
        return ledger(walletId, afterSequence, limit, null);
    }

    /** Charges of the given wallets, newest first; the caller has already established that they belong to the customer. */
    public List<ChargeView> charges(List<WalletId> walletIds, UUID before, int limit) {
        if (walletIds.isEmpty()) {
            return List.of();
        }
        return unitOfWork.transactional(() -> charges.list(walletIds.stream().map(WalletId::value).toList(), before, PageSize.of(limit)));
    }

    public List<LedgerEntryView> ledger(WalletId walletId, long afterSequence, int limit, UUID caller) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        return unitOfWork.transactional(() -> {
            visibleWallet(walletId, caller, Messages::walletNotFound);
            return ledger.page(walletId, afterSequence, PageSize.of(limit));
        });
    }

    public ReservationView reservation(ReservationId id) {
        return reservation(id, null);
    }

    public ReservationView reservation(ReservationId id, UUID caller) {
        return unitOfWork.transactional(() -> {
            ReservationView reservation = reservations.findById(id).orElseThrow(() -> Messages.reservationNotFound());
            visibleWallet(reservation.walletId(), caller, Messages::reservationNotFound);
            return reservation;
        });
    }

    public ChargeView charge(ChargeId id) {
        return charge(id, null);
    }

    public ChargeView charge(ChargeId id, UUID caller) {
        return unitOfWork.transactional(() -> {
            ChargeView charge = charges.findById(id).orElseThrow(() -> Messages.chargeNotFound());
            visibleWallet(charge.walletId(), caller, Messages::chargeNotFound);
            return charge;
        });
    }

    /**
     * A Tenant System sees the wallet of a row only when it mapped the wallet's customer; the row is then reported
     * with the same {@code notFound} error as a row that is not there. A Tenant User sees the whole tenant.
     */
    private void visibleWallet(WalletId id, UUID caller, Supplier<NotFoundException> notFound) {
        WalletView wallet = wallets.findById(id).orElseThrow(notFound);
        if (caller != null) {
            visibility.requireVisible(wallet.customerId(), caller, notFound);
        }
    }
}
