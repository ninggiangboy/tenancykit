package dev.ngb.platform.tenancy.domain.entitlement;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/** Whether one Tenant may use one app of the suite. */
public final class AppEntitlement {

    private final TenantId tenantId;
    private final String appCode;
    private final Instant updatedAt;
    private boolean enabled;
    private long version;

    private AppEntitlement(TenantId tenantId, String appCode, boolean enabled, Instant updatedAt, long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.appCode = requireAppCode(appCode);
        this.enabled = enabled;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static AppEntitlement enable(TenantId tenantId, String appCode, Instant now) {
        return new AppEntitlement(tenantId, appCode, true, now, 1);
    }

    public static AppEntitlement disable(TenantId tenantId, String appCode, Instant now) {
        return new AppEntitlement(tenantId, appCode, false, now, 1);
    }

    public static AppEntitlement restore(TenantId tenantId, String appCode, boolean enabled, Instant updatedAt, long version) {
        return new AppEntitlement(tenantId, appCode, enabled, updatedAt, version);
    }

    /** @return false when the state is unchanged, so no event is published for a no-op */
    public boolean setEnabled(boolean newValue, Instant now) {
        if (enabled == newValue) {
            return false;
        }
        enabled = newValue;
        version++;
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String appCode() {
        return appCode;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private static String requireAppCode(String appCode) {
        Objects.requireNonNull(appCode, "appCode");
        if (appCode.isBlank()) {
            throw new IllegalArgumentException("app code must not be blank");
        }
        return appCode;
    }
}
