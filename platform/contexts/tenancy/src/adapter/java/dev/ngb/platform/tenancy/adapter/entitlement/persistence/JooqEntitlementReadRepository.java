package dev.ngb.platform.tenancy.adapter.entitlement.persistence;

import static dev.ngb.platform_jooq.Tables.APP_ENTITLEMENTS;

import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementReadRepository;
import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementView;
import dev.ngb.platform_jooq.tables.records.AppEntitlementsRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The read side of entitlements; the cursor is the tenant id. */
public class JooqEntitlementReadRepository implements EntitlementReadRepository {

    private final DSLContext dsl;

    public JooqEntitlementReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Every entitlement of the next {@code limit} tenants, so a page boundary never splits one tenant's rows. */
    @Override
    public List<EntitlementView> page(UUID afterTenantId, int limit) {
        var tenantIds = dsl.selectDistinct(APP_ENTITLEMENTS.TENANT_ID)
                .from(APP_ENTITLEMENTS)
                .where(afterTenantId == null ? DSL.noCondition() : APP_ENTITLEMENTS.TENANT_ID.gt(afterTenantId))
                .orderBy(APP_ENTITLEMENTS.TENANT_ID)
                .limit(limit);
        return dsl.selectFrom(APP_ENTITLEMENTS)
                .where(APP_ENTITLEMENTS.TENANT_ID.in(tenantIds))
                .orderBy(APP_ENTITLEMENTS.TENANT_ID, APP_ENTITLEMENTS.APP_CODE)
                .fetch(JooqEntitlementReadRepository::toView);
    }

    @Override
    public List<EntitlementView> listByTenant(UUID tenantId) {
        return dsl.selectFrom(APP_ENTITLEMENTS)
                .where(APP_ENTITLEMENTS.TENANT_ID.eq(tenantId))
                .orderBy(APP_ENTITLEMENTS.APP_CODE)
                .fetch(JooqEntitlementReadRepository::toView);
    }

    private static EntitlementView toView(AppEntitlementsRecord record) {
        return new EntitlementView(record.getTenantId(), record.getAppCode(), record.getEnabled(), record.getVersion());
    }
}
