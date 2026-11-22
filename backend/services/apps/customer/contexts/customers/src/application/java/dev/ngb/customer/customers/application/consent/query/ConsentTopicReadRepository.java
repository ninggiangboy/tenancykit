package dev.ngb.customer.customers.application.consent.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;

/** Reads consent topics straight into the view; the aggregate is never rebuilt for a read. */
public interface ConsentTopicReadRepository {

    Page<ConsentTopicView> list(ListQuery query);

    Optional<ConsentTopicView> findByKey(String key);
}
