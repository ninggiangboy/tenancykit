package dev.ngb.billing.money.api.wallet;

import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.Optional;

/**
 * Read-only lookup for the contexts that charge or reserve against a wallet and must first know whose it is and
 * in which currency. It answers within the current tenant scope, so another tenant's wallet is simply absent.
 */
public interface WalletLookup {

    Optional<WalletSummary> find(WalletId id);

    /**
     * Reads the wallet and holds its row lock until the caller's transaction ends, for a flow that must serialise
     * against a charge on the same wallet before it decides anything. Its lock order is the charge path's: wallet
     * first, then anything the wallet's own rows point at.
     */
    Optional<WalletSummary> lockById(WalletId id);
}
