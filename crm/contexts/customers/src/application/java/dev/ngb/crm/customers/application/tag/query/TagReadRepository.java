package dev.ngb.crm.customers.application.tag.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** Reads the distinct tags of a tenant with the number of customers that carry each of them. */
public interface TagReadRepository {

    Page<TagView> list(ListQuery query);
}
