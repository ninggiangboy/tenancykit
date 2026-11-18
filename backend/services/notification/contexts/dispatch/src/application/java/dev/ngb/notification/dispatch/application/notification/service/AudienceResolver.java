package dev.ngb.notification.dispatch.application.notification.service;

import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.UserDirectory;
import dev.ngb.notification.platform.api.replica.UserView;
import java.util.List;

/**
 * Who receives a type, from the replica.
 *
 * <p>The tenant-admin audience has no dedicated permission: {@code platform:user:manage} at tenant scope is the closest
 * existing proxy for "manages this tenant", and introducing a new Platform permission would change another service for
 * a read-only audience rule.
 */
public class AudienceResolver {

    /** The tenant-scope permission that proxies "this tenant's administrator". */
    public static final String TENANT_ADMIN_PERMISSION = "platform:user:manage";

    private final UserDirectory users;

    public AudienceResolver(UserDirectory users) {
        this.users = users;
    }

    public List<UserView> recipients(NotificationType type, Change change) {
        return switch (type) {
            case ACCESS_GRANTED, ACCESS_CHANGED, ACCESS_DISABLED ->
                    change.currentUser() == null ? List.of() : List.of(change.currentUser());
            case ENTITLEMENT_CHANGED -> users.usersWithPermission(change.tenantId(), TENANT_ADMIN_PERMISSION);
            case TENANT_DEACTIVATED -> users.activeUsersOf(change.tenantId());
        };
    }
}
