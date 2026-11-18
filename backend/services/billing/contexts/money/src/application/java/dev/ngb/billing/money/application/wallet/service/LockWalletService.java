package dev.ngb.billing.money.application.wallet.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.money.api.wallet.LockWallet;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.billing.money.application.wallet.port.WalletRepository;
import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.Optional;

/** Serves the {@link LockWallet} port: a wallet read under its row lock, for a flow that must serialise against a charge. */
public class LockWalletService implements LockWallet {

    private final UnitOfWork unitOfWork;
    private final WalletRepository wallets;

    public LockWalletService(UnitOfWork unitOfWork, WalletRepository wallets) {
        this.unitOfWork = unitOfWork;
        this.wallets = wallets;
    }

    @Override
    public Optional<WalletSummary> lockById(WalletId id) {
        return unitOfWork.transactional(() -> wallets.lockById(id)
                .map(w -> new WalletSummary(w.id(), w.customerId(), w.currency(), w.mode())));
    }
}
