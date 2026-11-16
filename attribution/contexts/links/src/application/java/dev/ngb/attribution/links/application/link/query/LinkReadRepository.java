package dev.ngb.attribution.links.application.link.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The link read side. */
public interface LinkReadRepository {

    /** @return one page of the links the filter admits, with each link's destinations */
    Page<LinkView> list(ListQuery query);

    Optional<LinkView> find(UUID linkId);
}
