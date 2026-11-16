package dev.ngb.crm.customers.adapter.customer.persistence;

import static dev.ngb.crm_jooq.Tables.CUSTOMERS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.crm.customers.application.customer.query.CustomerListing;
import dev.ngb.crm.customers.application.customer.query.CustomerReadRepository;
import dev.ngb.crm.customers.application.customer.query.CustomerView;
import dev.ngb.crm.customers.domain.customer.CustomerStatus;
import dev.ngb.crm_jooq.tables.records.CustomersRecord;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Selects customers straight into the view; row-level security narrows every statement to the current tenant. */
public class JooqCustomerReadRepository implements CustomerReadRepository {

    /** The customer fields a caller may narrow and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(CustomerListing.SCHEMA)
            .column("id", CUSTOMERS.ID)
            .column("external_ref", CUSTOMERS.EXTERNAL_REF)
            .column("display_name", CUSTOMERS.DISPLAY_NAME)
            .column("phone", CUSTOMERS.PHONE)
            .column("email", CUSTOMERS.EMAIL)
            .column("status", CUSTOMERS.STATUS)
            .column("created_at", CUSTOMERS.CREATED_AT)
            .column("updated_at", CUSTOMERS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the customer listing, for a test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqCustomerReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<CustomerView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, CUSTOMERS, DSL.noCondition(), BINDING, query, JooqCustomerReadRepository::toView);
    }

    @Override
    public Optional<CustomerView> findByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .fetchOptional(JooqCustomerReadRepository::toView);
    }

    private static CustomerView toView(CustomersRecord row) {
        return new CustomerView(
                row.getId(),
                row.getExternalRef(),
                row.getDisplayName(),
                row.getPhone(),
                row.getEmail(),
                CustomerStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
