package dev.ngb.crm.customers.adapter.consent.persistence;

import static dev.ngb.crm_jooq.Tables.CONSENT_TOPICS;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.customers.application.consent.port.ConsentTopicRepository;
import dev.ngb.crm.customers.domain.consent.ConsentTopic;
import dev.ngb.crm_jooq.tables.records.ConsentTopicsRecord;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Stores consent topics. The key is the Tenant's own name for a purpose, so a first-time topic that loses a race
 * against a concurrent one inserts nothing instead of failing, and the caller answers with a conflict.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here.
 */
public class JooqConsentTopicRepository implements ConsentTopicRepository {

    private final DSLContext dsl;

    public JooqConsentTopicRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insertIfAbsent(ConsentTopic topic) {
        return dsl.insertInto(CONSENT_TOPICS)
                        .set(CONSENT_TOPICS.TENANT_ID, topic.tenantId().value())
                        .set(CONSENT_TOPICS.KEY, topic.key())
                        .set(CONSENT_TOPICS.LABEL, topic.label())
                        .set(CONSENT_TOPICS.ARCHIVED, topic.archived())
                        .onConflict(CONSENT_TOPICS.TENANT_ID, CONSENT_TOPICS.KEY)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public void update(ConsentTopic topic) {
        int updated = dsl.update(CONSENT_TOPICS)
                .set(CONSENT_TOPICS.LABEL, topic.label())
                .set(CONSENT_TOPICS.ARCHIVED, topic.archived())
                .where(CONSENT_TOPICS.KEY.eq(topic.key()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("consent topic " + topic.key() + " was not updated");
        }
    }

    @Override
    public Optional<ConsentTopic> lock(String key) {
        return dsl.selectFrom(CONSENT_TOPICS)
                .where(CONSENT_TOPICS.KEY.eq(key))
                .forUpdate()
                .fetchOptional(JooqConsentTopicRepository::toDomain);
    }

    @Override
    public Optional<ConsentTopic> findByKey(String key) {
        return dsl.selectFrom(CONSENT_TOPICS)
                .where(CONSENT_TOPICS.KEY.eq(key))
                .fetchOptional(JooqConsentTopicRepository::toDomain);
    }

    private static ConsentTopic toDomain(ConsentTopicsRecord row) {
        return ConsentTopic.restore(
                new TenantId(row.getTenantId()), row.getKey(), row.getLabel(), row.getArchived());
    }
}
