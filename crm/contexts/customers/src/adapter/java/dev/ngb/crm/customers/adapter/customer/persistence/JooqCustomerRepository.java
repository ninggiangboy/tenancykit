package dev.ngb.crm.customers.adapter.customer.persistence;

import static dev.ngb.crm_jooq.Tables.CUSTOMERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.domain.customer.Customer;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import dev.ngb.crm.customers.domain.customer.CustomerStatus;
import dev.ngb.crm_jooq.tables.records.CustomersRecord;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Stores customers. The unique key of a customer is the Tenant's reference, so a first-time upsert that loses the race
 * against a concurrent one inserts nothing instead of failing, and the caller re-reads the row that won.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's customers.
 */
public class JooqCustomerRepository implements CustomerRepository {

    private final DSLContext dsl;

    public JooqCustomerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insertIfAbsent(Customer c) {
        return dsl.insertInto(CUSTOMERS)
                        .set(CUSTOMERS.TENANT_ID, c.tenantId().value())
                        .set(CUSTOMERS.ID, c.id().value())
                        .set(CUSTOMERS.EXTERNAL_REF, c.externalRef())
                        .set(CUSTOMERS.DISPLAY_NAME, c.displayName())
                        .set(CUSTOMERS.PHONE, c.phone())
                        .set(CUSTOMERS.PHONE_DIGITS, c.phoneDigits())
                        .set(CUSTOMERS.EMAIL, c.email())
                        .set(CUSTOMERS.STATUS, c.status().name())
                        .set(CUSTOMERS.VERSION, c.version())
                        .set(CUSTOMERS.CREATED_AT, c.createdAt())
                        .set(CUSTOMERS.UPDATED_AT, c.updatedAt())
                        .onConflict(CUSTOMERS.TENANT_ID, CUSTOMERS.EXTERNAL_REF)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public void update(Customer c) {
        int updated = dsl.update(CUSTOMERS)
                .set(CUSTOMERS.DISPLAY_NAME, c.displayName())
                .set(CUSTOMERS.PHONE, c.phone())
                .set(CUSTOMERS.PHONE_DIGITS, c.phoneDigits())
                .set(CUSTOMERS.EMAIL, c.email())
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
    }

    @Override
    public Optional<Customer> findByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .fetchOptional(JooqCustomerRepository::toDomain);
    }

    @Override
    public Optional<Customer> lockByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .forUpdate()
                .fetchOptional(JooqCustomerRepository::toDomain);
    }

    private static Customer toDomain(CustomersRecord row) {
        return Customer.restore(
                new CustomerId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getExternalRef(),
                row.getDisplayName(),
                row.getPhone(),
                row.getPhoneDigits(),
                row.getEmail(),
                CustomerStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
