package dev.ngb.customer.customers.adapter.customer.persistence;

import static dev.ngb.customer_jooq.Tables.CUSTOMERS;
import static dev.ngb.customer_jooq.Tables.CUSTOMER_TAGS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.customer.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.customer.customers.application.customer.query.CustomerListing;
import dev.ngb.customer.customers.application.customer.query.CustomerReadRepository;
import dev.ngb.customer.customers.application.customer.query.CustomerSearchCriteria;
import dev.ngb.customer.customers.application.customer.query.CustomerView;
import dev.ngb.customer.customers.domain.customer.CustomerStatus;
import dev.ngb.customer_jooq.tables.records.CustomersRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SelectQuery;
import org.jooq.impl.DSL;

/**
 * Selects customers straight into the view; row-level security narrows every statement to the current tenant.
 *
 * <p>A page's tags are read in one statement for the whole page rather than one per customer, so the list stays one
 * query plus one lookup whatever the page size. The search terms the customer list answers are turned into predicates
 * by {@link CustomerSearchConditions} and applied as the listing's own scope, so a page narrowed by a tag, a field
 * value or a consent state is still one statement.
 */
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
    private final JacksonFieldValues fieldValues;
    private final CustomerSearchConditions conditions;

    /** @return the binding of the customer listing, for a test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqCustomerReadRepository(
            DSLContext dsl, JacksonFieldValues fieldValues, CustomerSearchConditions conditions) {
        this.dsl = dsl;
        this.fieldValues = fieldValues;
        this.conditions = conditions;
    }

    @Override
    public Page<CustomerView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, CUSTOMERS, DSL.noCondition(), BINDING, query, this::toViews);
    }

    @Override
    public Page<CustomerView> search(ListQuery query, CustomerSearchCriteria criteria) {
        return JooqLists.fetchPage(dsl, CUSTOMERS, conditions.of(criteria), BINDING, query, this::toViews);
    }

    /**
     * The statement the search runs for its first page, so a test can ask the database how it would answer it. It is
     * built exactly like {@link #search}: the same scope, the same ordering and one extra row to know whether a next
     * page exists.
     */
    public SelectQuery<CustomersRecord> searchStatement(ListQuery query, CustomerSearchCriteria criteria) {
        Condition filtered = conditions.of(criteria).and(BINDING.where(query));
        Condition positioned = query.seeking() ? filtered.and(BINDING.after(query)) : filtered;
        return dsl.selectFrom(CUSTOMERS)
                .where(positioned)
                .orderBy(BINDING.order(query))
                .limit(query.limit() + 1)
                .offset(query.offset())
                .getQuery();
    }

    @Override
    public Optional<CustomerView> findByExternalRef(String externalRef) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.EXTERNAL_REF.eq(externalRef))
                .fetchOptional(row -> toView(row, tagsOf(List.of(row.getId())).getOrDefault(row.getId(), List.of())));
    }

    private List<CustomerView> toViews(List<CustomersRecord> rows) {
        Map<UUID, List<String>> tags = tagsOf(rows.stream().map(CustomersRecord::getId).toList());
        List<CustomerView> views = new ArrayList<>(rows.size());
        for (CustomersRecord row : rows) {
            views.add(toView(row, tags.getOrDefault(row.getId(), List.of())));
        }
        return views;
    }

    private CustomerView toView(CustomersRecord row, List<String> tags) {
        return new CustomerView(
                row.getId(),
                row.getExternalRef(),
                row.getDisplayName(),
                row.getPhone(),
                row.getEmail(),
                fieldValues.fromJson(row.getFields() == null ? null : row.getFields().data()),
                tags,
                CustomerStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private Map<UUID, List<String>> tagsOf(Collection<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> byCustomer = new HashMap<>();
        dsl.select(CUSTOMER_TAGS.CUSTOMER_ID, CUSTOMER_TAGS.TAG)
                .from(CUSTOMER_TAGS)
                .where(CUSTOMER_TAGS.CUSTOMER_ID.in(customerIds))
                .orderBy(CUSTOMER_TAGS.CUSTOMER_ID, CUSTOMER_TAGS.TAG)
                .fetch()
                .forEach(row -> byCustomer
                        .computeIfAbsent(row.get(CUSTOMER_TAGS.CUSTOMER_ID), id -> new ArrayList<>())
                        .add(row.get(CUSTOMER_TAGS.TAG)));
        return byCustomer;
    }
}
