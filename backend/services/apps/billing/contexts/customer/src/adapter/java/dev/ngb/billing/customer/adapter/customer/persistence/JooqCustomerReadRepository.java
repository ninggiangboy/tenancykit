package dev.ngb.billing.customer.adapter.customer.persistence;

import static dev.ngb.billing_jooq.Tables.CUSTOMERS;
import static dev.ngb.billing_jooq.Tables.CUSTOMER_MAPPINGS;
import static org.jooq.impl.DSL.any;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

import dev.ngb.billing.customer.application.customer.query.CustomerIdentityView;
import dev.ngb.billing.customer.application.customer.query.CustomerListing;
import dev.ngb.billing.customer.application.customer.query.CustomerReadRepository;
import dev.ngb.billing.customer.application.customer.query.CustomerView;
import dev.ngb.billing.customer.domain.customer.CustomerStatus;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing_jooq.tables.records.CustomersRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record3;

/** Selects customers and their mappings straight into views; the aggregate is never built. */
public class JooqCustomerReadRepository implements CustomerReadRepository {

    /** The customer fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(CustomerListing.SCHEMA)
            .column("id", CUSTOMERS.ID)
            .column("display_name", CUSTOMERS.DISPLAY_NAME)
            .column("email", CUSTOMERS.EMAIL)
            .column("status", CUSTOMERS.STATUS)
            .column("created_at", CUSTOMERS.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the customer listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqCustomerReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<CustomerView> list(ListQuery query, UUID tenantSystemId) {
        Condition scope = tenantSystemId == null ? noCondition() : exists(selectOne()
                .from(CUSTOMER_MAPPINGS)
                .where(CUSTOMER_MAPPINGS.CUSTOMER_ID.eq(CUSTOMERS.ID))
                .and(CUSTOMER_MAPPINGS.TENANT_SYSTEM_ID.eq(tenantSystemId)));
        return JooqLists.fetchPage(dsl, CUSTOMERS, scope, BINDING, query, rows -> withIdentities(rows, tenantSystemId));
    }

    @Override
    public Optional<CustomerView> findById(CustomerId id) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.ID.eq(id.value()))
                .fetchOptional()
                .map(row -> withIdentities(List.of(row), null).getFirst());
    }

    @Override
    public Optional<CustomerView> findByIdMappedTo(CustomerId id, UUID tenantSystemId) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.ID.eq(id.value()))
                .and(exists(selectOne()
                        .from(CUSTOMER_MAPPINGS)
                        .where(CUSTOMER_MAPPINGS.CUSTOMER_ID.eq(CUSTOMERS.ID))
                        .and(CUSTOMER_MAPPINGS.TENANT_SYSTEM_ID.eq(tenantSystemId))))
                .fetchOptional()
                .map(row -> withIdentities(List.of(row), tenantSystemId).getFirst());
    }

    @Override
    public Optional<CustomerView> findByExternalIdentity(UUID tenantSystemId, String externalUserId) {
        return dsl.select(CUSTOMER_MAPPINGS.CUSTOMER_ID)
                .from(CUSTOMER_MAPPINGS)
                .where(CUSTOMER_MAPPINGS.TENANT_SYSTEM_ID.eq(tenantSystemId))
                .and(CUSTOMER_MAPPINGS.EXTERNAL_USER_ID.eq(externalUserId))
                .fetchOptional(CUSTOMER_MAPPINGS.CUSTOMER_ID)
                .flatMap(id -> findByIdMappedTo(new CustomerId(id), tenantSystemId));
    }

    @Override
    public List<UUID> mappedCustomerIds(UUID tenantSystemId) {
        return dsl.selectDistinct(CUSTOMER_MAPPINGS.CUSTOMER_ID)
                .from(CUSTOMER_MAPPINGS)
                .where(CUSTOMER_MAPPINGS.TENANT_SYSTEM_ID.eq(tenantSystemId))
                .fetch(CUSTOMER_MAPPINGS.CUSTOMER_ID);
    }

    private List<CustomerView> withIdentities(List<CustomersRecord> rows, UUID tenantSystemId) {
        Map<UUID, List<CustomerIdentityView>> identities = mappings(rows.stream().map(CustomersRecord::getId).toArray(UUID[]::new), tenantSystemId);
        return rows.stream().map(row -> toView(row, identities.getOrDefault(row.getId(), List.of()))).toList();
    }

    private Map<UUID, List<CustomerIdentityView>> mappings(UUID[] customerIds, UUID tenantSystemId) {
        if (customerIds.length == 0) {
            return Map.of();
        }
        Condition scope = tenantSystemId == null ? noCondition() : CUSTOMER_MAPPINGS.TENANT_SYSTEM_ID.eq(tenantSystemId);
        Map<UUID, List<CustomerIdentityView>> byCustomer = new HashMap<>();
        for (Record3<UUID, UUID, String> row : dsl.select(CUSTOMER_MAPPINGS.CUSTOMER_ID, CUSTOMER_MAPPINGS.TENANT_SYSTEM_ID, CUSTOMER_MAPPINGS.EXTERNAL_USER_ID)
                .from(CUSTOMER_MAPPINGS)
                .where(CUSTOMER_MAPPINGS.CUSTOMER_ID.eq(any(customerIds)))
                .and(scope)
                .orderBy(CUSTOMER_MAPPINGS.EXTERNAL_USER_ID)
                .fetch()) {
            byCustomer.computeIfAbsent(row.value1(), key -> new ArrayList<>()).add(new CustomerIdentityView(row.value2(), row.value3()));
        }
        return byCustomer;
    }

    private static CustomerView toView(CustomersRecord row, List<CustomerIdentityView> identities) {
        return new CustomerView(
                new CustomerId(row.getId()),
                row.getDisplayName(),
                row.getEmail(),
                CustomerStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                List.copyOf(identities),
                row.getVersion());
    }
}
