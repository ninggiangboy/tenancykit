package dev.ngb.email.platform.adapter.platform.persistence;

import static dev.ngb.email_jooq.Tables.APP_ENTITLEMENTS;
import static dev.ngb.email_jooq.Tables.TENANTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.platform.api.replica.TenantView;
import dev.ngb.email.platform.application.platform.port.TenantRows;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** The tenant half of the replica read seam, over the tables the {@code platform} context owns. */
public class JooqTenantDirectory implements TenantRows {

    /** The reserved Platform tenant; a sweep that iterated it would publish email for a tenant mailer refuses. */
    private static final String PLATFORM_TENANT_CODE = "platform";

    private final DSLContext dsl;

    public JooqTenantDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TenantView> activeTenants() {
        return dsl.select(TENANTS.ID, TENANTS.CODE, TENANTS.NAME, TENANTS.STATUS)
                .from(TENANTS)
                .where(TENANTS.STATUS.eq("ACTIVE"))
                .and(TENANTS.CODE.ne(PLATFORM_TENANT_CODE))
                .orderBy(TENANTS.ID)
                .fetch(row -> view(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    @Override
    public Optional<TenantView> tenant(TenantId tenantId) {
        return dsl.select(TENANTS.ID, TENANTS.CODE, TENANTS.NAME, TENANTS.STATUS)
                .from(TENANTS)
                .where(TENANTS.ID.eq(tenantId.value()))
                .fetchOptional(row -> view(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    @Override
    public Optional<Boolean> entitlementEnabled(TenantId tenantId, String appCode) {
        return dsl.select(APP_ENTITLEMENTS.ENABLED)
                .from(APP_ENTITLEMENTS)
                .where(APP_ENTITLEMENTS.TENANT_ID.eq(tenantId.value()))
                .and(APP_ENTITLEMENTS.APP_CODE.eq(appCode))
                .fetchOptional(APP_ENTITLEMENTS.ENABLED);
    }

    private static TenantView view(java.util.UUID id, String code, String name, String status) {
        return new TenantView(new TenantId(id), code, name, status);
    }
}
