package dev.ngb.attribution.links.application.link.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The link listing and detail endpoints, read inside a tenant-scoped transaction. */
public class LinkQueries {

    private final UnitOfWork unitOfWork;
    private final LinkReadRepository links;

    public LinkQueries(UnitOfWork unitOfWork, LinkReadRepository links) {
        this.unitOfWork = unitOfWork;
        this.links = links;
    }

    /** @param query the caller's filter, order and page position, already validated against the listing's schema */
    public Page<LinkView> list(ListQuery query) {
        return unitOfWork.transactional(() -> links.list(query));
    }

    public LinkView get(UUID linkId) {
        return unitOfWork.transactional(() -> links.find(linkId)
                .orElseThrow(() -> new NotFoundException("No link exists here.")));
    }
}
