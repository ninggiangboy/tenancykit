package dev.ngb.attribution.links.application.link.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The link read side. */
public interface LinkReadRepository {

    List<LinkView> list(UUID campaignId, String externalRef, String status, UUID afterId, int limit);

    Optional<LinkView> find(UUID linkId);
}
