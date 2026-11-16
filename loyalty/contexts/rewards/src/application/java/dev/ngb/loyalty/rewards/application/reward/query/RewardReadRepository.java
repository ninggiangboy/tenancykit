package dev.ngb.loyalty.rewards.application.reward.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's catalog. */
public interface RewardReadRepository {

    /** One page of the catalog; {@code active} narrows it to the active rewards or to the archived ones. */
    Page<RewardView> list(ListQuery query, Boolean active);

    Optional<RewardView> findById(UUID id);
}
