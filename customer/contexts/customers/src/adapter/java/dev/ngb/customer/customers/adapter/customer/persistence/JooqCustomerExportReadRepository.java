package dev.ngb.customer.customers.adapter.customer.persistence;

import static dev.ngb.customer_jooq.Tables.CUSTOMERS;
import static dev.ngb.customer_jooq.Tables.FIELD_DEFINITIONS;

import dev.ngb.customer.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.customer.customers.application.customer.query.CustomerExportReadRepository;
import dev.ngb.customer.customers.application.customer.query.CustomerExportRow;
import dev.ngb.customer.customers.application.customer.query.CustomerSearchCriteria;
import dev.ngb.customer_jooq.tables.records.CustomersRecord;
import java.util.List;
import java.util.function.Consumer;
import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * Reads the customers an export writes, through a server-side cursor so the rows arrive a page at a time.
 *
 * <p>The page size is the query's fetch size, which is what makes the driver hold one page rather than the whole
 * result: an export of a hundred thousand customers streams in fixed memory. The tags of a row are read in the same
 * statement as the row — one aggregate per customer, through the tag table's own index — so the stream never issues a
 * query per customer.
 *
 * <p>The query is built in {@link #prepare}, which is what turns a bad filter into a {@code 400} before the caller has
 * written its header.
 */
public class JooqCustomerExportReadRepository implements CustomerExportReadRepository {

    private final DSLContext dsl;
    private final JacksonFieldValues fieldValues;
    private final CustomerSearchConditions conditions;

    public JooqCustomerExportReadRepository(
            DSLContext dsl, JacksonFieldValues fieldValues, CustomerSearchConditions conditions) {
        this.dsl = dsl;
        this.fieldValues = fieldValues;
        this.conditions = conditions;
    }

    @Override
    public Prepared prepare(CustomerSearchCriteria criteria, int pageSize) {
        Condition where = conditions.of(criteria);
        return new Prepared(activeFieldKeys(), sink -> stream(where, pageSize, sink));
    }

    /** The keys of the Tenant's active field definitions, in key order: one CSV column each. */
    private List<String> activeFieldKeys() {
        return dsl.select(FIELD_DEFINITIONS.KEY)
                .from(FIELD_DEFINITIONS)
                .where(FIELD_DEFINITIONS.ARCHIVED.isFalse())
                .orderBy(FIELD_DEFINITIONS.KEY)
                .fetch(FIELD_DEFINITIONS.KEY);
    }

    /**
     * Streams the matching customers in the exported order, newest first and broken by the id so the file is
     * reproducible, reading at most {@code pageSize} rows per round trip.
     */
    private void stream(Condition where, int pageSize, Consumer<CustomerExportRow> sink) {
        Field<String> tags = DSL.field(
                        "(select string_agg(tagged.tag, '|' order by tagged.tag) from customer_tags tagged"
                                + " where tagged.tenant_id = {0} and tagged.customer_id = {1})",
                        String.class,
                        CUSTOMERS.TENANT_ID,
                        CUSTOMERS.ID)
                .as("tags");
        try (Cursor<Record> rows = dsl.select(CUSTOMERS.asterisk(), tags)
                .from(CUSTOMERS)
                .where(where)
                .orderBy(CUSTOMERS.CREATED_AT.desc(), CUSTOMERS.ID.desc())
                .fetchSize(pageSize)
                .fetchLazy()) {
            while (rows.hasNext()) {
                sink.accept(row(rows.fetchNext(), tags));
            }
        }
    }

    private CustomerExportRow row(Record row, Field<String> tags) {
        CustomersRecord customer = row.into(CUSTOMERS);
        String joined = row.get(tags);
        return new CustomerExportRow(
                customer.getExternalRef(),
                customer.getDisplayName(),
                customer.getPhone(),
                customer.getEmail(),
                joined == null || joined.isEmpty() ? List.of() : List.of(joined.split("\\|", -1)),
                fieldValues.fromJson(customer.getFields() == null ? null : customer.getFields().data()),
                customer.getCreatedAt());
    }
}
