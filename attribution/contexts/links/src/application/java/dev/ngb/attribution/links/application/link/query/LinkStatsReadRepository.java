package dev.ngb.attribution.links.application.link.query;

import java.util.Optional;
import java.util.UUID;

/** The link statistics read side. */
public interface LinkStatsReadRepository {

    Optional<LinkStatsView> stats(UUID linkId);
}
