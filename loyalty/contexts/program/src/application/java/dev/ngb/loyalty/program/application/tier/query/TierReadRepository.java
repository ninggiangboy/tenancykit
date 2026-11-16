package dev.ngb.loyalty.program.application.tier.query;

import dev.ngb.loyalty.program.api.tier.TierView;
import java.util.List;

/** Read-only projection of the current tenant's tier list, lowest first. */
public interface TierReadRepository {

    List<TierView> findAll();
}
