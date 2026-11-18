package dev.ngb.tenant.identity.api.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The actions a business role may perform. A permission is checked per operation, never inferred from a URL.
 * Each code comes from {@link TenantPermissions}, so an endpoint annotation and a role grant cannot drift.
 */
public enum Permission {
    USER_MANAGE(TenantPermissions.USER_MANAGE),
    CUSTOMER_MANAGE(TenantPermissions.CUSTOMER_MANAGE),
    ORDER_CREATE(TenantPermissions.ORDER_CREATE),
    ORDER_MANAGE(TenantPermissions.ORDER_MANAGE),
    ORDER_READ(TenantPermissions.ORDER_READ),
    USAGE_READ(TenantPermissions.USAGE_READ),
    BILLING_READ(TenantPermissions.BILLING_READ),
    TOPUP_CREATE(TenantPermissions.TOPUP_CREATE),
    SETTINGS_MANAGE(TenantPermissions.SETTINGS_MANAGE);

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The permission a code names, so the authorizer can answer the code an endpoint declares. */
    public static Optional<Permission> byCode(String code) {
        return Arrays.stream(values()).filter(permission -> permission.code.equals(code)).findFirst();
    }
}
