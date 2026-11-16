package dev.ngb.attribution.links.application.link.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import java.util.List;
import java.util.UUID;

/** The link listing and detail endpoints, read inside a tenant-scoped transaction. */
public class LinkQueries {

    public static final int DEFAULT_LIMIT = 50;

    private final UnitOfWork unitOfWork;
    private final LinkReadRepository links;

    public LinkQueries(UnitOfWork unitOfWork, LinkReadRepository links) {
        this.unitOfWork = unitOfWork;
        this.links = links;
    }

    public List<LinkView> list(
            UUID campaignId, String externalRef, String status, UUID after, Integer limit) {
        return unitOfWork.transactional(
                () -> links.list(campaignId, externalRef, status, after,
                        PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public LinkView get(UUID linkId) {
        return unitOfWork.transactional(() -> links.find(linkId)
                .orElseThrow(() -> new NotFoundException("No link exists here.")));
    }
}
