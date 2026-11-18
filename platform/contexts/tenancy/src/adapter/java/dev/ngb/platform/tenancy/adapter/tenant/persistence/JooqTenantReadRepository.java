package dev.ngb.platform.tenancy.adapter.tenant.persistence;

import static dev.ngb.platform_jooq.Tables.TENANT_DOMAINS;
import static dev.ngb.platform_jooq.Tables.TENANTS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.platform.tenancy.application.tenant.query.TenantDomainView;
import dev.ngb.platform.tenancy.application.tenant.query.TenantListing;
import dev.ngb.platform.tenancy.application.tenant.query.TenantReadRepository;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import dev.ngb.platform.tenancy.domain.tenant.DomainKind;
import dev.ngb.platform.tenancy.domain.tenant.TenantStatus;
import dev.ngb.platform_jooq.tables.records.TenantDomainsRecord;
import dev.ngb.platform_jooq.tables.records.TenantsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The read side of tenancy. A snapshot page is ordered by id, which UUID v7 makes time-ordered. */
public class JooqTenantReadRepository implements TenantReadRepository {

    private static final String PLATFORM_CODE = "platform";

    /** The fields a caller may filter and sort on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(TenantListing.SCHEMA)
            .column("id", TENANTS.ID)
            .column("code", TENANTS.CODE)
            .column("name", TENANTS.NAME)
            .column("status", TENANTS.STATUS)
            .column("created_at", TENANTS.CREATED_AT)
            .column("version", TENANTS.VERSION)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the tenant listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqTenantReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The reserved platform tenant is the caller's own scope, so it is never one of the listed tenants. */
    @Override
    public Page<TenantView> list(ListQuery query) {
        return JooqLists.fetch(dsl, TENANTS, TENANTS.CODE.ne(PLATFORM_CODE), BINDING, query, JooqTenantReadRepository::toView);
    }

    @Override
    public List<TenantView> all() {
        return dsl.selectFrom(TENANTS).orderBy(TENANTS.ID).fetch(JooqTenantReadRepository::toView);
    }

    /** The replica stream includes the reserved platform tenant, because platform-scope jobs need it. */
    @Override
    public List<TenantView> page(UUID after, int limit) {
        return dsl.selectFrom(TENANTS)
                .where(after == null ? DSL.noCondition() : TENANTS.ID.gt(after))
                .orderBy(TENANTS.ID)
                .limit(limit)
                .fetch(JooqTenantReadRepository::toView);
    }

    @Override
    public Optional<TenantView> findById(UUID id) {
        return dsl.selectFrom(TENANTS).where(TENANTS.ID.eq(id)).fetchOptional(JooqTenantReadRepository::toView);
    }

    @Override
    public List<TenantDomainView> pageDomains(UUID after, int limit) {
        return dsl.selectFrom(TENANT_DOMAINS)
                .where(after == null ? DSL.noCondition() : TENANT_DOMAINS.ID.gt(after))
                .orderBy(TENANT_DOMAINS.ID)
                .limit(limit)
                .fetch(JooqTenantReadRepository::toDomainView);
    }

    /** The join to a Tenant is what makes a host "active": a deactivated Tenant's hosts stop being redirect targets. */
    @Override
    public List<String> activeConsoleHosts() {
        return dsl.select(TENANT_DOMAINS.HOSTNAME)
                .from(TENANT_DOMAINS)
                .join(TENANTS).on(TENANTS.ID.eq(TENANT_DOMAINS.TENANT_ID))
                .where(TENANTS.STATUS.eq(TenantStatus.ACTIVE.name()))
                .and(TENANT_DOMAINS.KIND.eq(DomainKind.TENANT_SUBDOMAIN.name()))
                .orderBy(TENANT_DOMAINS.HOSTNAME)
                .fetch(TENANT_DOMAINS.HOSTNAME);
    }

    @Override
    public Optional<TenantDomainView> findDomainByHostname(String hostname) {
        return dsl.selectFrom(TENANT_DOMAINS)
                .where(TENANT_DOMAINS.HOSTNAME.eq(hostname))
                .fetchOptional(JooqTenantReadRepository::toDomainView);
    }

    @Override
    public List<TenantDomainView> listDomains(UUID tenantId) {
        return dsl.selectFrom(TENANT_DOMAINS)
                .where(TENANT_DOMAINS.TENANT_ID.eq(tenantId))
                .orderBy(TENANT_DOMAINS.ID)
                .fetch(JooqTenantReadRepository::toDomainView);
    }

    private static TenantView toView(TenantsRecord record) {
        return new TenantView(
                record.getId(), record.getCode(), record.getName(), record.getStatus(), record.getCreatedAt(), record.getVersion());
    }

    private static TenantDomainView toDomainView(TenantDomainsRecord record) {
        return new TenantDomainView(
                record.getId(), record.getTenantId(), record.getHostname(), record.getKind(), record.getCreatedAt(), record.getVersion());
    }
}
