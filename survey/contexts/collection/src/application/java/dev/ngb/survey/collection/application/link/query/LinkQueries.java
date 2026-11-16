package dev.ngb.survey.collection.application.link.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.link.service.LinkUrls;
import java.util.UUID;

/**
 * The links a Tenant reads: its list for a survey and one link by id, each with the URL rebuilt from the id.
 *
 * <p>The URL is derived rather than stored, so it is always current: the same link answers at the configured base and
 * with the active key, and a rotated base changes every URL the API reports without a migration.
 */
public class LinkQueries {

    private final UnitOfWork unitOfWork;
    private final LinkReadRepository links;
    private final LinkUrls urls;

    public LinkQueries(UnitOfWork unitOfWork, LinkReadRepository links, LinkUrls urls) {
        this.unitOfWork = unitOfWork;
        this.links = links;
        this.urls = urls;
    }

    public Page<LinkView> list(UUID surveyId, ListQuery query) {
        return unitOfWork.transactional(() -> links.list(query, surveyId).map(this::withUrl));
    }

    public LinkView get(UUID linkId) {
        return unitOfWork.transactional(() -> withUrl(links.find(linkId)
                .orElseThrow(() -> new NotFoundException("No link exists here."))));
    }

    /** The same row with its URL filled in, which is the only field the table cannot supply. */
    public LinkView withUrl(LinkView view) {
        return new LinkView(
                view.id(),
                view.surveyId(),
                view.kind(),
                view.source(),
                view.externalRef(),
                view.expiresAt(),
                view.status(),
                view.createdAt(),
                urls.open(view.id()));
    }
}
