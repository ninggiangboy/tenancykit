package dev.ngb.platform.tenancy.adapter.tenant.persistence;

import static dev.ngb.platform_jooq.Tables.TENANT_DOMAINS;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.tenancy.application.tenant.port.TenantDomainRepository;
import dev.ngb.platform.tenancy.domain.tenant.DomainKind;
import dev.ngb.platform.tenancy.domain.tenant.Hostname;
import dev.ngb.platform.tenancy.domain.tenant.TenantDomain;
import dev.ngb.platform_jooq.tables.records.TenantDomainsRecord;
import java.util.Optional;
import org.jooq.DSLContext;

public class JooqTenantDomainRepository implements TenantDomainRepository {

    private final DSLContext dsl;

    public JooqTenantDomainRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(TenantDomain domain) {
        dsl.insertInto(TENANT_DOMAINS)
                .set(TENANT_DOMAINS.ID, domain.id())
                .set(TENANT_DOMAINS.TENANT_ID, domain.tenantId().map(TenantId::value).orElse(null))
                .set(TENANT_DOMAINS.HOSTNAME, domain.hostname().value())
                .set(TENANT_DOMAINS.KIND, domain.kind().name())
                .set(TENANT_DOMAINS.CREATED_AT, domain.createdAt())
                .set(TENANT_DOMAINS.VERSION, domain.version())
                .execute();
    }

    @Override
    public Optional<TenantDomain> findByHostname(Hostname hostname) {
        return dsl.selectFrom(TENANT_DOMAINS)
                .where(TENANT_DOMAINS.HOSTNAME.eq(hostname.value()))
                .fetchOptional(JooqTenantDomainRepository::toDomain);
    }

    private static TenantDomain toDomain(TenantDomainsRecord record) {
        return TenantDomain.restore(
                record.getId(),
                record.getTenantId() == null ? null : new TenantId(record.getTenantId()),
                new Hostname(record.getHostname()),
                DomainKind.valueOf(record.getKind()),
                record.getCreatedAt(),
                record.getVersion());
    }
}
