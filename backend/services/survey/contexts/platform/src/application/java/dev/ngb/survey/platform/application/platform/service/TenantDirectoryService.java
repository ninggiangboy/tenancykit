package dev.ngb.survey.platform.application.platform.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.survey.platform.api.replica.TenantDirectory;
import dev.ngb.survey.platform.api.replica.TenantView;
import dev.ngb.survey.platform.application.platform.port.TenantRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Serves another context from the replica. The class name and package are what {@code api-implementations-are-services} requires. */
public class TenantDirectoryService implements TenantDirectory {

    private final TenantRows rows;

    public TenantDirectoryService(TenantRows rows) {
        this.rows = rows;
    }

    @Override
    public List<TenantId> activeTenantIds() {
        return rows.activeTenants().stream().map(TenantView::id).toList();
    }

    @Override
    public List<TenantId> sweptTenantIds() {
        List<TenantId> swept = new ArrayList<>();
        swept.add(RequestScope.PLATFORM_TENANT_ID);
        swept.addAll(activeTenantIds());
        return List.copyOf(swept);
    }

    @Override
    public Optional<TenantView> tenant(TenantId tenantId) {
        return rows.tenant(tenantId);
    }

    @Override
    public Optional<Boolean> entitlementEnabled(TenantId tenantId, String appCode) {
        return rows.entitlementEnabled(tenantId, appCode);
    }
}
