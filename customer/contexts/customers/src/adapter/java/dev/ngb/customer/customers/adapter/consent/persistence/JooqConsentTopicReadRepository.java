package dev.ngb.customer.customers.adapter.consent.persistence;

import static dev.ngb.customer_jooq.Tables.CONSENT_TOPICS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.customer.customers.application.consent.query.ConsentTopicListing;
import dev.ngb.customer.customers.application.consent.query.ConsentTopicReadRepository;
import dev.ngb.customer.customers.application.consent.query.ConsentTopicView;
import dev.ngb.customer_jooq.tables.records.ConsentTopicsRecord;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Selects consent topics straight into the view; row-level security narrows every statement to the current tenant. */
public class JooqConsentTopicReadRepository implements ConsentTopicReadRepository {

    private static final ListBinding BINDING = ListBinding.of(ConsentTopicListing.SCHEMA)
            .column("key", CONSENT_TOPICS.KEY)
            .column("label", CONSENT_TOPICS.LABEL)
            .column("archived", CONSENT_TOPICS.ARCHIVED)
            .build();

    private final DSLContext dsl;

    public JooqConsentTopicReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<ConsentTopicView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, CONSENT_TOPICS, DSL.noCondition(), BINDING, query, JooqConsentTopicReadRepository::toView);
    }

    @Override
    public Optional<ConsentTopicView> findByKey(String key) {
        return dsl.selectFrom(CONSENT_TOPICS)
                .where(CONSENT_TOPICS.KEY.eq(key))
                .fetchOptional(JooqConsentTopicReadRepository::toView);
    }

    private static ConsentTopicView toView(ConsentTopicsRecord row) {
        return new ConsentTopicView(row.getKey(), row.getLabel(), row.getArchived());
    }
}
