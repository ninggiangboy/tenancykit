package dev.ngb.customer.customers.adapter.field.persistence;

import static dev.ngb.customer_jooq.Tables.FIELD_DEFINITIONS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionListing;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionReadRepository;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionView;
import dev.ngb.customer.customers.domain.field.FieldType;
import dev.ngb.customer_jooq.tables.records.FieldDefinitionsRecord;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Selects field definitions straight into the view; row-level security narrows every statement to the current tenant. */
public class JooqFieldDefinitionReadRepository implements FieldDefinitionReadRepository {

    private static final ListBinding BINDING = ListBinding.of(FieldDefinitionListing.SCHEMA)
            .column("key", FIELD_DEFINITIONS.KEY)
            .column("label", FIELD_DEFINITIONS.LABEL)
            .column("type", FIELD_DEFINITIONS.TYPE)
            .column("archived", FIELD_DEFINITIONS.ARCHIVED)
            .column("created_at", FIELD_DEFINITIONS.CREATED_AT)
            .build();

    private final DSLContext dsl;

    public JooqFieldDefinitionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<FieldDefinitionView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, FIELD_DEFINITIONS, DSL.noCondition(), BINDING, query, JooqFieldDefinitionReadRepository::toView);
    }

    @Override
    public Optional<FieldDefinitionView> findByKey(String key) {
        return dsl.selectFrom(FIELD_DEFINITIONS)
                .where(FIELD_DEFINITIONS.KEY.eq(key))
                .fetchOptional(JooqFieldDefinitionReadRepository::toView);
    }

    private static FieldDefinitionView toView(FieldDefinitionsRecord row) {
        String[] choices = row.getChoices();
        return new FieldDefinitionView(
                row.getKey(),
                row.getLabel(),
                FieldType.valueOf(row.getType()),
                choices == null ? List.of() : Arrays.asList(choices),
                row.getArchived(),
                row.getCreatedAt());
    }
}
