package dev.ngb.platform.integration.adapter.tenantsystem.persistence;

import static dev.ngb.platform_jooq.Tables.TENANT_SYSTEMS;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.util.Causes;
import dev.ngb.platform.integration.application.tenantsystem.port.TenantSystemRepository;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemStatus;
import dev.ngb.platform_jooq.tables.records.TenantSystemsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DuplicateKeyException;

public class JooqTenantSystemRepository implements TenantSystemRepository {

    private final DSLContext dsl;

    public JooqTenantSystemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(TenantSystem system) {
        try {
            dsl.insertInto(TENANT_SYSTEMS)
                    .set(TENANT_SYSTEMS.ID, system.id().value())
                    .set(TENANT_SYSTEMS.TENANT_ID, system.tenantId().value())
                    .set(TENANT_SYSTEMS.NAME, system.name())
                    .set(TENANT_SYSTEMS.STATUS, system.status().name())
                    .set(TENANT_SYSTEMS.CREATED_AT, system.createdAt())
                    .set(TENANT_SYSTEMS.VERSION, system.version())
                    .execute();
        } catch (RuntimeException e) {
            if (Causes.contains(e, DuplicateKeyException.class)) {
                throw new ConflictException("a tenant system with this name already exists");
            }
            throw e;
        }
    }

    @Override
    public void update(TenantSystem system) {
        int updated = dsl.update(TENANT_SYSTEMS)
                .set(TENANT_SYSTEMS.NAME, system.name())
                .set(TENANT_SYSTEMS.STATUS, system.status().name())
                .set(TENANT_SYSTEMS.VERSION, system.version())
                .where(TENANT_SYSTEMS.ID.eq(system.id().value()))
                .and(TENANT_SYSTEMS.VERSION.lt(system.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "tenant system " + system.id() + " was not updated: version " + system.version() + " is not newer");
        }
    }

    @Override
    public Optional<TenantSystem> findById(TenantSystemId id) {
        return dsl.selectFrom(TENANT_SYSTEMS)
                .where(TENANT_SYSTEMS.ID.eq(id.value()))
                .fetchOptional(JooqTenantSystemRepository::toDomain);
    }

    @Override
    public Optional<TenantSystem> lockById(TenantSystemId id) {
        return dsl.selectFrom(TENANT_SYSTEMS)
                .where(TENANT_SYSTEMS.ID.eq(id.value()))
                .forNoKeyUpdate()
                .fetchOptional(JooqTenantSystemRepository::toDomain);
    }

    @Override
    public List<TenantSystem> page(UUID after, int limit) {
        return dsl.selectFrom(TENANT_SYSTEMS)
                .where(after == null ? DSL.noCondition() : TENANT_SYSTEMS.ID.gt(after))
                .orderBy(TENANT_SYSTEMS.ID)
                .limit(limit)
                .fetch(JooqTenantSystemRepository::toDomain);
    }

    private static TenantSystem toDomain(TenantSystemsRecord row) {
        return TenantSystem.restore(
                new TenantSystemId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getName(),
                TenantSystemStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getVersion());
    }
}
