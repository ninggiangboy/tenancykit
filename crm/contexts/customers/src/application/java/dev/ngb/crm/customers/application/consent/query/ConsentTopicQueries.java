package dev.ngb.crm.customers.application.consent.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.customers.application.consent.exception.ConsentTopicNotFoundException;
import dev.ngb.crm.customers.application.consent.service.ConsentRules;

/** Reads the current tenant's consent topics for the GET handler. */
public class ConsentTopicQueries {

    private final UnitOfWork unitOfWork;
    private final ConsentTopicReadRepository topics;

    public ConsentTopicQueries(UnitOfWork unitOfWork, ConsentTopicReadRepository topics) {
        this.unitOfWork = unitOfWork;
        this.topics = topics;
    }

    public Page<ConsentTopicView> list(ListQuery query) {
        return unitOfWork.transactional(() -> topics.list(query));
    }

    public ConsentTopicView get(String key) {
        String normalised = ConsentRules.key(key);
        return unitOfWork.transactional(
                () -> topics.findByKey(normalised).orElseThrow(ConsentTopicNotFoundException::new));
    }
}
