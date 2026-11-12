package dev.ngb.platform.tenancy.adapter.tenant.persistence;

import static dev.ngb.platform_jooq.Tables.TENANTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import dev.ngb.platform.tenancy.domain.tenant.TenantCode;
import dev.ngb.platform.tenancy.domain.tenant.TenantStatus;
import dev.ngb.platform_jooq.tables.records.TenantsRecord;
import java.util.Optional;
import org.jooq.DSLContext;

public class JooqTenantRepository implements TenantRepository {

    private final DSLContext dsl;

    public JooqTenantRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Tenant tenant) {
        dsl.insertInto(TENANTS)
                .set(TENANTS.ID, tenant.id().value())
                .set(TENANTS.CODE, tenant.code().value())
                .set(TENANTS.NAME, tenant.name())
                .set(TENANTS.STATUS, tenant.status().name())
                .set(TENANTS.CREATED_AT, tenant.createdAt())
                .set(TENANTS.VERSION, tenant.version())
                .execute();
    }

    @Override
    public void update(Tenant tenant) {
        dsl.update(TENANTS)
                .set(TENANTS.NAME, tenant.name())
                .set(TENANTS.STATUS, tenant.status().name())
                .set(TENANTS.VERSION, tenant.version())
                .where(TENANTS.ID.eq(tenant.id().value()))
                .execute();
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return dsl.selectFrom(TENANTS).where(TENANTS.ID.eq(id.value())).fetchOptional(JooqTenantRepository::toTenant);
    }

    @Override
    public Optional<Tenant> findByCode(TenantCode code) {
        return dsl.selectFrom(TENANTS).where(TENANTS.CODE.eq(code.value())).fetchOptional(JooqTenantRepository::toTenant);
    }

    private static Tenant toTenant(TenantsRecord record) {
        return Tenant.restore(
                new TenantId(record.getId()),
                new TenantCode(record.getCode()),
                record.getName(),
                TenantStatus.valueOf(record.getStatus()),
                record.getCreatedAt(),
                record.getVersion());
    }
}
