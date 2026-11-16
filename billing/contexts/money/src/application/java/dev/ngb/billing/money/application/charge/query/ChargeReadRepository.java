package dev.ngb.billing.money.application.charge.query;

import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projection of a charge. */
public interface ChargeReadRepository {

    Optional<ChargeView> findById(ChargeId id);

    /** One page of charges of {@code walletIds}; null reads the whole tenant. */
    Page<ChargeView> list(ListQuery query, List<UUID> walletIds);
}
