package dev.ngb.tenant.identity.api.security;

/**
 * The sample tenant system's permission codes, as compile-time strings because {@code @RequiresPermission} needs a
 * constant. {@link Permission} is built from these codes and the roles grant that enum, so the code an endpoint
 * declares and the permission a role holds are the same string.
 */
public final class TenantPermissions {

    public static final String USER_MANAGE = "user:manage";
    public static final String CUSTOMER_MANAGE = "customer:manage";
    public static final String ORDER_CREATE = "order:create";
    public static final String ORDER_MANAGE = "order:manage";
    public static final String ORDER_READ = "order:read";
    public static final String USAGE_READ = "usage:read";
    public static final String BILLING_READ = "billing:read";
    public static final String TOPUP_CREATE = "top-up:create";
    public static final String SETTINGS_MANAGE = "settings:manage";

    private TenantPermissions() {}
}
