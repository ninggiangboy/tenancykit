package dev.ngb.platform.integration.adapter.tenantsystem.persistence;

import static dev.ngb.platform_jooq.Tables.TENANT_SYSTEMS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemReadRepository;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemView;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemListing;
import dev.ngb.platform_jooq.tables.records.TenantSystemsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Selects tenant systems straight into views, including the {@code tenant-systems} replica stream. */
public class JooqTenantSystemReadRepository implements TenantSystemReadRepository {

    /** The fields a caller may filter and sort Tenant Systems on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(TenantSystemListing.SCHEMA)
            .column("id", TENANT_SYSTEMS.ID)
            .column("tenant_id", TENANT_SYSTEMS.TENANT_ID)
            .column("name", TENANT_SYSTEMS.NAME)
            .column("status", TENANT_SYSTEMS.STATUS)
            .column("created_at", TENANT_SYSTEMS.CREATED_AT)
            .column("version", TENANT_SYSTEMS.VERSION)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the tenant-system listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqTenantSystemReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The table is row-level secured, so the tenant of the listing comes from the transaction, as it always has. */
    @Override
    public Page<TenantSystemView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, TENANT_SYSTEMS, DSL.noCondition(), BINDING, query, JooqTenantSystemReadRepository::toView);
    }

    @Override
    public Optional<TenantSystemView> findById(UUID id) {
        return dsl.selectFrom(TENANT_SYSTEMS)
                .where(TENANT_SYSTEMS.ID.eq(id))
                .fetchOptional(JooqTenantSystemReadRepository::toView);
    }

    @Override
    public List<TenantSystemView> page(UUID after, int limit) {
        return dsl.selectFrom(TENANT_SYSTEMS)
                .where(after == null ? DSL.noCondition() : TENANT_SYSTEMS.ID.gt(after))
                .orderBy(TENANT_SYSTEMS.ID)
                .limit(limit)
                .fetch(JooqTenantSystemReadRepository::toView);
    }

    private static TenantSystemView toView(TenantSystemsRecord row) {
        return new TenantSystemView(
                row.getId(), row.getTenantId(), row.getName(), row.getStatus(), row.getCreatedAt(), row.getVersion());
    }
}
