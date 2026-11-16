package dev.ngb.billing.money.api.wallet;

import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.Optional;

/**
 * Takes a wallet's row lock for a context that must serialise against a charge on the same wallet before it decides
 * anything. Its lock order is the charge path's: wallet first, then anything the wallet's own rows point at.
 *
 * <p>It is separate from {@link WalletLookup} on purpose: a read has no business taking a lock, and only the couple
 * of flows that need the guarantee should be able to ask for one.
 */
public interface LockWallet {

    /** Reads the wallet and holds its row lock until the caller's transaction ends. */
    Optional<WalletSummary> lockById(WalletId id);
}
