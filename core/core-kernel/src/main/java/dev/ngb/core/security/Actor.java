package dev.ngb.core.security;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.ActorNames;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is acting, resolved once per request. Permissions are string codes namespaced by the app that enforces them
 * ({@code billing:invoice:read}); the tenant always comes from the authenticated identity, never from a
 * client-supplied value.
 */
public record Actor(
        ActorType type, UUID id, TenantId tenantId, PermissionScope scope, String displayName, Set<String> permissions) {

    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(displayName, "displayName");
        permissions = Set.copyOf(permissions);
    }

    public boolean has(String code) {
        return permissions.contains(code);
    }

    /** The string recorded in audit entries, for example {@code user/<id>}. */
    public String auditName() {
        return ActorNames.of(type, id);
    }
}
