package dev.ngb.platform.integration.adapter.tenantsystem.persistence;

import static dev.ngb.platform_jooq.Tables.TENANT_SYSTEMS;

import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemReadRepository;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemView;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemStatus;
import dev.ngb.platform_jooq.tables.records.TenantSystemsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Selects tenant systems straight into views, including the {@code tenant-systems} replica stream. */
public class JooqTenantSystemReadRepository implements TenantSystemReadRepository {

    private final DSLContext dsl;

    public JooqTenantSystemReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TenantSystemView> list() {
        return dsl.selectFrom(TENANT_SYSTEMS)
                .orderBy(TENANT_SYSTEMS.CREATED_AT, TENANT_SYSTEMS.ID)
                .fetch(JooqTenantSystemReadRepository::toView);
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
                row.getId(), row.getTenantId(), row.getName(), TenantSystemStatus.valueOf(row.getStatus()), row.getCreatedAt(), row.getVersion());
    }
}
