package dev.ngb.billing.money.application.wallet.query;

import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.runtime.UnitOfWork;
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
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
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
    private final CustomerLookup customers;
    private final CustomerVisibility visibility;

    public WalletQueries(
            UnitOfWork unitOfWork, WalletReadRepository wallets, LedgerReadRepository ledger, ReservationReadRepository reservations,
            ChargeReadRepository charges, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.wallets = wallets;
        this.ledger = ledger;
        this.reservations = reservations;
        this.charges = charges;
        this.customers = customers;
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

    /** A Tenant System sees only wallets of the customers it mapped; any other caller sees the whole tenant. */
    public Page<WalletView> wallets(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> wallets.list(query, visibleCustomers(caller)));
    }

    /** Wallets of one customer, for the portal principal that is fixed to that customer. */
    public Page<WalletView> walletsForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> wallets.list(query, List.of(customerId.value())));
    }

    public Page<LedgerEntryView> ledger(WalletId walletId, ListQuery query) {
        return ledger(walletId, query, null);
    }

    public Page<LedgerEntryView> ledger(WalletId walletId, ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> {
            visibleWallet(walletId, caller, Messages::walletNotFound);
            return ledger.list(query, walletId);
        });
    }

    /** Charges of the wallets the caller may see. */
    public Page<ChargeView> charges(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> charges.list(query, walletIdsFor(caller)));
    }

    /** Charges of one customer's wallets, for the portal principal that is fixed to that customer. */
    public Page<ChargeView> chargesForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> charges.list(query, walletIdsOf(List.of(customerId.value()))));
    }

    /** Reservations of the wallets the caller may see. */
    public Page<ReservationView> reservations(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> reservations.list(query, walletIdsFor(caller)));
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

    /** @return the customers a listing may cover, or null for the whole tenant */
    private List<UUID> visibleCustomers(UUID caller) {
        if (caller == null) {
            return null;
        }
        return customers.mappedTo(caller).stream().map(CustomerId::value).toList();
    }

    /** @return every wallet of {@code customerIds} */
    private List<UUID> walletIdsOf(List<UUID> customerIds) {
        return wallets.idsOf(customerIds);
    }

    /** The wallet ids a listing of the caller's customers may cover; null for the whole tenant, empty for none. */
    private List<UUID> walletIdsFor(UUID caller) {
        List<UUID> visible = visibleCustomers(caller);
        if (visible == null) {
            return null;
        }
        if (visible.isEmpty()) {
            return List.of();
        }
        return walletIdsOf(visible);
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
