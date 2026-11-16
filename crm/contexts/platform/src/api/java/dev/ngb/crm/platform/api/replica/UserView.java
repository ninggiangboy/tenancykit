package dev.ngb.crm.platform.api.replica;

import dev.ngb.core.id.TenantId;
import java.util.Set;
import java.util.UUID;

/**
 * One user as Platform publishes it: the identity, the scope, the status and the permission codes.
 *
 * @param scope {@code PLATFORM} or {@code TENANT}; the access types notify at both scopes, the tenant-wide types
 *     ({@code ENTITLEMENT_CHANGED}, {@code TENANT_DEACTIVATED}) resolve their audience from tenant users only
 * @param permissions the codes this app enforces for the user; an unordered set, compared as a set when a transition
 *     is decided
 */
public record UserView(
        UUID userId,
        TenantId tenantId,
        String scope,
        String email,
        String displayName,
        String status,
        Set<String> permissions,
        long version) {

    public UserView {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean tenantScoped() {
        return "TENANT".equals(scope);
    }
}
