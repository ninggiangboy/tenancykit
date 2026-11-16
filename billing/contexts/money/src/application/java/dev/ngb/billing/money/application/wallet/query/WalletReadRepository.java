package dev.ngb.billing.money.application.wallet.query;

import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of wallets. */
public interface WalletReadRepository {

    Optional<WalletView> findById(WalletId id);

    /** One page of wallets; {@code customerIds} restricts it to those customers, and null reads the whole tenant. */
    Page<WalletView> list(ListQuery query, List<UUID> customerIds);

    /** The ids of every wallet of {@code customerIds}. */
    List<UUID> idsOf(List<UUID> customerIds);
}
