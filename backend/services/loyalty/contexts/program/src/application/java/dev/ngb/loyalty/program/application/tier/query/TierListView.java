package dev.ngb.loyalty.program.application.tier.query;

import dev.ngb.loyalty.program.api.tier.TierView;
import java.util.List;

/**
 * The whole tier list and the version a caller uses as its {@code ETag}. The version is the program's, because a tier
 * change is a change of the program that owns the list.
 */
public record TierListView(List<TierView> tiers, long version) {

    public TierListView {
        tiers = List.copyOf(tiers);
    }
}
