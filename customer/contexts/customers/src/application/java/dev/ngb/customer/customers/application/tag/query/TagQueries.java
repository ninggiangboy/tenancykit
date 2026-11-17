package dev.ngb.customer.customers.application.tag.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;

/** Reads the current tenant's tags for the GET handler. */
public class TagQueries {

    private final UnitOfWork unitOfWork;
    private final TagReadRepository tags;

    public TagQueries(UnitOfWork unitOfWork, TagReadRepository tags) {
        this.unitOfWork = unitOfWork;
        this.tags = tags;
    }

    public Page<TagView> list(ListQuery query) {
        return unitOfWork.transactional(() -> tags.list(query));
    }
}
