package dev.ngb.billing.money.application.wallet.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.billing.money.application.wallet.port.WalletRepository;
import dev.ngb.billing.money.application.wallet.query.WalletReadRepository;
import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.Optional;

public class WalletLookupService implements WalletLookup {

    private final UnitOfWork unitOfWork;
    private final WalletReadRepository wallets;
    private final WalletRepository writeSide;

    public WalletLookupService(UnitOfWork unitOfWork, WalletReadRepository wallets, WalletRepository writeSide) {
        this.unitOfWork = unitOfWork;
        this.wallets = wallets;
        this.writeSide = writeSide;
    }

    @Override
    public Optional<WalletSummary> find(WalletId id) {
        return unitOfWork.transactional(
                () -> wallets.findById(id).map(w -> new WalletSummary(w.id(), w.customerId(), w.currency(), w.mode())));
    }

    @Override
    public Optional<WalletSummary> lockById(WalletId id) {
        return unitOfWork.transactional(() -> writeSide.lockById(id)
                .map(w -> new WalletSummary(w.id(), w.customerId(), w.currency(), w.mode())));
    }
}
