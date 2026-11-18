package dev.ngb.platform.sharedkernel.security;

/**
 * Platform's permission codes, as compile-time strings because {@code @RequiresPermission} needs a constant. The
 * codes are exactly the entries of {@code contracts/permissions/platform.yaml}; a test keeps the two in step.
 *
 * <p>Every code is namespaced {@code platform:<resource>:<action>}. Only codes the catalog marks with the
 * {@code tenant} scope may be held by a tenant role, and only {@code platform:replica:read} is granted to no role
 * at all: it belongs to a service client.
 */
public final class PlatformPermissions {

    public static final String TENANT_CREATE = "platform:tenant:create";
    public static final String TENANT_RENAME = "platform:tenant:rename";
    public static final String TENANT_READ = "platform:tenant:read";
    public static final String TENANT_DEACTIVATE = "platform:tenant:deactivate";
    /** Adds and removes the hostnames that resolve to a Tenant. */
    public static final String TENANT_DOMAIN_MANAGE = "platform:tenant-domain:manage";

    public static final String USER_READ = "platform:user:read";
    public static final String USER_MANAGE = "platform:user:manage";
    public static final String ROLE_READ = "platform:role:read";
    public static final String ROLE_MANAGE = "platform:role:manage";

    public static final String TENANT_SYSTEM_READ = "platform:tenant-system:read";
    public static final String TENANT_SYSTEM_MANAGE = "platform:tenant-system:manage";
    /** Issues and revokes the API credentials a Tenant System authenticates with. */
    public static final String CREDENTIAL_MANAGE = "platform:credential:manage";

    /** Enables or disables an app of the suite for a Tenant. */
    public static final String ENTITLEMENT_MANAGE = "platform:entitlement:manage";

    /** Registers a version of a console UI remote; granted to no role, only to the frontend CI service client. */
    public static final String UI_REMOTE_REGISTER = "platform:ui-remote:register";
    /** Enables, disables and points a console UI remote at one of its versions. */
    public static final String UI_REMOTE_MANAGE = "platform:ui-remote:manage";
    /** Reads the console UI remote registry. */
    public static final String UI_REMOTE_READ = "platform:ui-remote:read";

    /** The snapshot API's code: granted to no role, only to a service client of the suite. */
    public static final String REPLICA_READ = "platform:replica:read";

    private PlatformPermissions() {}
}
