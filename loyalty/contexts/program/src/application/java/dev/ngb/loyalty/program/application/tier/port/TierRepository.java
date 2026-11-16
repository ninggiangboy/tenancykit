package dev.ngb.loyalty.program.application.tier.port;

import dev.ngb.loyalty.program.domain.tier.Tier;
import dev.ngb.loyalty.program.domain.tier.TierId;
import java.util.List;

/** The current tenant's tier list, as the command side writes it. */
public interface TierRepository {

    /** The whole list in order, lowest first. */
    List<Tier> findAll();

    void add(Tier tier);

    /** Rewrites one tier's name, threshold, multiplier and position; its id never changes. */
    void update(Tier tier);

    void delete(TierId id);
}
