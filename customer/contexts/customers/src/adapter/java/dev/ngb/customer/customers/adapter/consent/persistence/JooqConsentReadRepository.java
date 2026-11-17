package dev.ngb.customer.customers.adapter.consent.persistence;

import static dev.ngb.customer_jooq.Tables.CONSENT_EVENTS;
import static dev.ngb.customer_jooq.Tables.CUSTOMERS;

import dev.ngb.customer.customers.application.consent.query.ConsentReadRepository;
import dev.ngb.customer.customers.application.consent.query.ConsentRecordView;
import dev.ngb.customer.customers.domain.consent.ConsentStatus;
import dev.ngb.customer_jooq.tables.records.ConsentEventsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Reads a customer's consent history, newest first by {@code occurredAt} and then by id. The current state of a topic is
 * the first record of that topic in that order, so one ordered read answers both the history and what the customer
 * agrees to now.
 */
public class JooqConsentReadRepository implements ConsentReadRepository {

    private final DSLContext dsl;

    public JooqConsentReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<UUID> findCustomerId(String externalRef) {
        return dsl.select(CUSTOMERS.ID)
                .from(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .fetchOptional(CUSTOMERS.ID);
    }

    @Override
    public List<ConsentRecordView> history(UUID customerId) {
        return dsl.selectFrom(CONSENT_EVENTS)
                .where(CONSENT_EVENTS.CUSTOMER_ID.eq(customerId))
                .orderBy(CONSENT_EVENTS.OCCURRED_AT.desc(), CONSENT_EVENTS.ID.desc())
                .fetch(JooqConsentReadRepository::toView);
    }

    private static ConsentRecordView toView(ConsentEventsRecord row) {
        return new ConsentRecordView(
                row.getTopicKey(),
                ConsentStatus.valueOf(row.getStatus()),
                row.getOccurredAt(),
                row.getRecordedAt(),
                row.getSource(),
                row.getEvidenceRef(),
                row.getConsentRef());
    }
}
