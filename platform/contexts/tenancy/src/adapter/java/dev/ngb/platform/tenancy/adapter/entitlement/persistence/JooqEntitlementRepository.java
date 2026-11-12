package dev.ngb.platform.tenancy.adapter.entitlement.persistence;

import static dev.ngb.platform_jooq.Tables.APP_ENTITLEMENTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.tenancy.application.entitlement.port.AppEntitlementRepository;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import dev.ngb.platform_jooq.tables.records.AppEntitlementsRecord;
import java.util.Optional;
import org.jooq.DSLContext;

public class JooqEntitlementRepository implements AppEntitlementRepository {

    private final DSLContext dsl;

    public JooqEntitlementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<AppEntitlement> find(TenantId tenantId, String appCode) {
        return dsl.selectFrom(APP_ENTITLEMENTS)
                .where(APP_ENTITLEMENTS.TENANT_ID.eq(tenantId.value()))
                .and(APP_ENTITLEMENTS.APP_CODE.eq(appCode))
                .fetchOptional(JooqEntitlementRepository::toEntitlement);
    }

    @Override
    public void upsert(AppEntitlement entitlement) {
        dsl.insertInto(APP_ENTITLEMENTS)
                .set(APP_ENTITLEMENTS.TENANT_ID, entitlement.tenantId().value())
                .set(APP_ENTITLEMENTS.APP_CODE, entitlement.appCode())
                .set(APP_ENTITLEMENTS.ENABLED, entitlement.isEnabled())
                .set(APP_ENTITLEMENTS.UPDATED_AT, entitlement.updatedAt())
                .set(APP_ENTITLEMENTS.VERSION, entitlement.version())
                .onConflict(APP_ENTITLEMENTS.TENANT_ID, APP_ENTITLEMENTS.APP_CODE)
                .doUpdate()
                .set(APP_ENTITLEMENTS.ENABLED, entitlement.isEnabled())
                .set(APP_ENTITLEMENTS.UPDATED_AT, entitlement.updatedAt())
                .set(APP_ENTITLEMENTS.VERSION, entitlement.version())
                .execute();
    }

    private static AppEntitlement toEntitlement(AppEntitlementsRecord record) {
        return AppEntitlement.restore(
                new TenantId(record.getTenantId()),
                record.getAppCode(),
                record.getEnabled(),
                record.getUpdatedAt(),
                record.getVersion());
    }
}
