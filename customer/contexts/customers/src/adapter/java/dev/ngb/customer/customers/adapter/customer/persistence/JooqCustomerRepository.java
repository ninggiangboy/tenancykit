package dev.ngb.customer.customers.adapter.customer.persistence;

import static dev.ngb.customer_jooq.Tables.CUSTOMERS;
import static dev.ngb.customer_jooq.Tables.CUSTOMER_TAGS;

import dev.ngb.core.id.TenantId;
import dev.ngb.customer.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.customer.customers.application.customer.port.CustomerRepository;
import dev.ngb.customer.customers.domain.customer.Customer;
import dev.ngb.customer.customers.domain.customer.CustomerId;
import dev.ngb.customer.customers.domain.customer.CustomerStatus;
import dev.ngb.customer_jooq.tables.records.CustomersRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * Stores customers with their field values and tags. The unique key of a customer is the Tenant's reference, so a
 * first-time upsert that loses the race against a concurrent one inserts nothing instead of failing, and the caller
 * re-reads the row that won.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's customers. The tags of one customer are a set of its own table; a
 * write replaces the set, because a caller always states it in full by the time it is stored.
 */
public class JooqCustomerRepository implements CustomerRepository {

    private static final String EMPTY_FIELDS = "{}";

    private final DSLContext dsl;
    private final JacksonFieldValues fieldValues;

    public JooqCustomerRepository(DSLContext dsl, JacksonFieldValues fieldValues) {
        this.dsl = dsl;
        this.fieldValues = fieldValues;
    }

    @Override
    public boolean insertIfAbsent(Customer c) {
        int inserted = dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.TENANT_ID, c.tenantId().value())
                .set(CUSTOMERS.ID, c.id().value())
                .set(CUSTOMERS.EXTERNAL_REF, c.externalRef())
                .set(CUSTOMERS.DISPLAY_NAME, c.displayName())
                .set(CUSTOMERS.PHONE, c.phone())
                .set(CUSTOMERS.PHONE_DIGITS, c.phoneDigits())
                .set(CUSTOMERS.EMAIL, c.email())
                .set(CUSTOMERS.FIELDS, JSONB.valueOf(fieldValues.toJson(c.fields())))
                .set(CUSTOMERS.STATUS, c.status().name())
                .set(CUSTOMERS.VERSION, c.version())
                .set(CUSTOMERS.CREATED_AT, c.createdAt())
                .set(CUSTOMERS.UPDATED_AT, c.updatedAt())
                .onConflict(CUSTOMERS.TENANT_ID, CUSTOMERS.EXTERNAL_REF)
                .doNothing()
                .execute();
        if (inserted != 1) {
            return false;
        }
        storeTags(c);
        return true;
    }

    @Override
    public void update(Customer c) {
        int updated = dsl.update(CUSTOMERS)
                .set(CUSTOMERS.DISPLAY_NAME, c.displayName())
                .set(CUSTOMERS.PHONE, c.phone())
                .set(CUSTOMERS.PHONE_DIGITS, c.phoneDigits())
                .set(CUSTOMERS.EMAIL, c.email())
                .set(CUSTOMERS.FIELDS, JSONB.valueOf(fieldValues.toJson(c.fields())))
                .set(CUSTOMERS.STATUS, c.status().name())
                .set(CUSTOMERS.UPDATED_AT, c.updatedAt())
                .set(CUSTOMERS.VERSION, c.version())
                .where(CUSTOMERS.ID.eq(c.id().value()))
                .and(CUSTOMERS.VERSION.lt(c.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "customer " + c.id() + " was not updated: version " + c.version() + " is not newer");
        }
        storeTags(c);
    }

    @Override
    public void erase(Customer c) {
        int erased = dsl.update(CUSTOMERS)
                .set(CUSTOMERS.DISPLAY_NAME, (String) null)
                .set(CUSTOMERS.PHONE, (String) null)
                .set(CUSTOMERS.PHONE_DIGITS, (String) null)
                .set(CUSTOMERS.EMAIL, (String) null)
                .set(CUSTOMERS.FIELDS, JSONB.valueOf(EMPTY_FIELDS))
                .set(CUSTOMERS.STATUS, c.status().name())
                .set(CUSTOMERS.EXTERNAL_REF, c.externalRef())
                .set(CUSTOMERS.UPDATED_AT, c.updatedAt())
                .set(CUSTOMERS.VERSION, c.version())
                .where(CUSTOMERS.ID.eq(c.id().value()))
                .and(CUSTOMERS.VERSION.lt(c.version()))
                .execute();
        if (erased != 1) {
            throw new IllegalStateException(
                    "customer " + c.id() + " was not erased: version " + c.version() + " is not newer");
        }
        storeTags(c);
    }

    @Override
    public Optional<Customer> findByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<Customer> lockByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .forUpdate()
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<Customer> findById(UUID id) {
        return dsl.selectFrom(CUSTOMERS).where(CUSTOMERS.ID.eq(id)).fetchOptional(this::toDomain);
    }

    /** The tag set of one customer: what it carries after this write, and nothing it carried before. */
    private void storeTags(Customer c) {
        dsl.deleteFrom(CUSTOMER_TAGS)
                .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(c.id().value()))
                .execute();
        if (!c.tags().isEmpty()) {
            var insert = dsl.insertInto(CUSTOMER_TAGS)
                    .columns(CUSTOMER_TAGS.TENANT_ID, CUSTOMER_TAGS.CUSTOMER_ID, CUSTOMER_TAGS.TAG);
            for (String tag : c.tags()) {
                insert = insert.values(c.tenantId().value(), c.id().value(), tag);
            }
            insert.execute();
        }
    }

    private Customer toDomain(CustomersRecord row) {
        return Customer.restore(
                new CustomerId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getExternalRef(),
                row.getDisplayName(),
                row.getPhone(),
                row.getPhoneDigits(),
                row.getEmail(),
                fieldValues.fromJson(row.getFields() == null ? null : row.getFields().data()),
                tagsOf(row.getId()),
                CustomerStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private Set<String> tagsOf(UUID customerId) {
        List<String> tags = new ArrayList<>(dsl.select(CUSTOMER_TAGS.TAG)
                .from(CUSTOMER_TAGS)
                .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(customerId))
                .orderBy(CUSTOMER_TAGS.TAG)
                .fetch(CUSTOMER_TAGS.TAG));
        return new TreeSet<>(tags);
    }
}
