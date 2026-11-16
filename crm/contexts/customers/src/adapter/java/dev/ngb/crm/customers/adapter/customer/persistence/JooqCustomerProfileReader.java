package dev.ngb.crm.customers.adapter.customer.persistence;

import static dev.ngb.crm_jooq.Tables.CONSENT_EVENTS;
import static dev.ngb.crm_jooq.Tables.CUSTOMERS;
import static dev.ngb.crm_jooq.Tables.CUSTOMER_TAGS;

import dev.ngb.crm.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.crm.customers.api.customer.ConsentState;
import dev.ngb.crm.customers.api.customer.CustomerProfile;
import dev.ngb.crm.customers.api.customer.CustomerProfileReader;
import dev.ngb.crm_jooq.tables.records.CustomersRecord;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Reads one customer whole for another context: its attributes, its field values, its tags and the current state of
 * every consent topic it has a record for.
 *
 * <p>It is one customer's worth of indexed reads — the row by its unique reference, its tags, and one
 * {@code distinct on} per topic for the consents — so the cost does not grow with the tenant's size. Row-level
 * security narrows every statement to the current tenant.
 */
public class JooqCustomerProfileReader implements CustomerProfileReader {

    private final DSLContext dsl;
    private final JacksonFieldValues fieldValues;

    public JooqCustomerProfileReader(DSLContext dsl, JacksonFieldValues fieldValues) {
        this.dsl = dsl;
        this.fieldValues = fieldValues;
    }

    @Override
    public Optional<CustomerProfile> findByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .fetchOptional(this::toProfile);
    }

    private CustomerProfile toProfile(CustomersRecord row) {
        return new CustomerProfile(
                row.getId(),
                row.getExternalRef(),
                row.getDisplayName(),
                row.getPhone(),
                row.getEmail(),
                fieldValues.fromJson(row.getFields() == null ? null : row.getFields().data()),
                tagsOf(row.getId()),
                row.getStatus(),
                consentsOf(row.getId()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private List<String> tagsOf(java.util.UUID customerId) {
        return dsl.select(CUSTOMER_TAGS.TAG)
                .from(CUSTOMER_TAGS)
                .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(customerId))
                .orderBy(CUSTOMER_TAGS.TAG)
                .fetch(CUSTOMER_TAGS.TAG);
    }

    /**
     * The current status of every topic: the latest record of that topic, where "latest" is what happened last and the
     * id breaks a tie between two records of one instant. A topic with no record is absent, which is not the same
     * answer as a revoked one.
     */
    private List<ConsentState> consentsOf(java.util.UUID customerId) {
        return dsl.select(CONSENT_EVENTS.TOPIC_KEY, CONSENT_EVENTS.STATUS, CONSENT_EVENTS.OCCURRED_AT)
                .distinctOn(CONSENT_EVENTS.TOPIC_KEY)
                .from(CONSENT_EVENTS)
                .where(CONSENT_EVENTS.CUSTOMER_ID.eq(customerId))
                .orderBy(CONSENT_EVENTS.TOPIC_KEY, CONSENT_EVENTS.OCCURRED_AT.desc(), CONSENT_EVENTS.ID.desc())
                .fetch(row -> new ConsentState(
                        row.get(CONSENT_EVENTS.TOPIC_KEY),
                        row.get(CONSENT_EVENTS.STATUS),
                        row.get(CONSENT_EVENTS.OCCURRED_AT)));
    }
}
