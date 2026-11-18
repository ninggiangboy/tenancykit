package dev.ngb.platform.access.application.role.query;

import java.util.Set;
import java.util.UUID;

/** One role and the codes it grants. */
public record RoleView(UUID id, UUID tenantId, String scope, String name, boolean system, Set<String> permissions, long version) {

    public RoleView {
        permissions = Set.copyOf(permissions);
    }
}
