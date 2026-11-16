package dev.ngb.billing.money.application.ledger.query;

import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** Read-only projection of a wallet's ledger statement. */
public interface LedgerReadRepository {

    /** One page of one wallet's entries. */
    Page<LedgerEntryView> list(ListQuery query, WalletId walletId);
}
