package dev.ngb.crm.customers.adapter.field.persistence;

import static dev.ngb.crm_jooq.Tables.FIELD_DEFINITIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.customers.application.field.port.FieldDefinitionRepository;
import dev.ngb.crm.customers.domain.field.FieldDefinition;
import dev.ngb.crm.customers.domain.field.FieldType;
import dev.ngb.crm_jooq.tables.records.FieldDefinitionsRecord;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Stores field definitions. The key of a definition is the Tenant's own name for it, so a first-time definition that
 * loses a race against a concurrent one inserts nothing instead of failing, and the caller answers with a conflict.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here.
 */
public class JooqFieldDefinitionRepository implements FieldDefinitionRepository {

    private final DSLContext dsl;

    public JooqFieldDefinitionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insertIfAbsent(FieldDefinition definition) {
        return dsl.insertInto(FIELD_DEFINITIONS)
                        .set(FIELD_DEFINITIONS.TENANT_ID, definition.tenantId().value())
                        .set(FIELD_DEFINITIONS.KEY, definition.key())
                        .set(FIELD_DEFINITIONS.LABEL, definition.label())
                        .set(FIELD_DEFINITIONS.TYPE, definition.type().name())
                        .set(FIELD_DEFINITIONS.CHOICES, definition.choices().toArray(String[]::new))
                        .set(FIELD_DEFINITIONS.ARCHIVED, definition.archived())
                        .set(FIELD_DEFINITIONS.CREATED_AT, definition.createdAt())
                        .onConflict(FIELD_DEFINITIONS.TENANT_ID, FIELD_DEFINITIONS.KEY)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public void update(FieldDefinition definition) {
        int updated = dsl.update(FIELD_DEFINITIONS)
                .set(FIELD_DEFINITIONS.LABEL, definition.label())
                .set(FIELD_DEFINITIONS.CHOICES, definition.choices().toArray(String[]::new))
                .set(FIELD_DEFINITIONS.ARCHIVED, definition.archived())
                .where(FIELD_DEFINITIONS.KEY.eq(definition.key()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("field definition " + definition.key() + " was not updated");
        }
    }

    @Override
    public Optional<FieldDefinition> lock(String key) {
        return dsl.selectFrom(FIELD_DEFINITIONS)
                .where(FIELD_DEFINITIONS.KEY.eq(key))
                .forUpdate()
                .fetchOptional(JooqFieldDefinitionRepository::toDomain);
    }

    @Override
    public List<FieldDefinition> active(Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(FIELD_DEFINITIONS)
                .where(FIELD_DEFINITIONS.KEY.in(keys))
                .and(FIELD_DEFINITIONS.ARCHIVED.isFalse())
                .fetch(JooqFieldDefinitionRepository::toDomain);
    }

    @Override
    public int countActive() {
        return dsl.fetchCount(FIELD_DEFINITIONS, FIELD_DEFINITIONS.ARCHIVED.isFalse());
    }

    private static FieldDefinition toDomain(FieldDefinitionsRecord row) {
        String[] choices = row.getChoices();
        return FieldDefinition.restore(
                new TenantId(row.getTenantId()),
                row.getKey(),
                row.getLabel(),
                FieldType.valueOf(row.getType()),
                choices == null ? List.of() : Arrays.asList(choices),
                row.getArchived(),
                row.getCreatedAt());
    }
}
