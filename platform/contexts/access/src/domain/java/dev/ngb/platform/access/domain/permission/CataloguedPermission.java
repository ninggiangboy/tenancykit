package dev.ngb.platform.access.domain.permission;

import dev.ngb.core.security.PermissionScope;
import java.util.Objects;
import java.util.Set;

/** One entry of a permission catalog: a code, where it applies, and whether an API credential may hold it. */
public record CataloguedPermission(
        String code, String description, Set<PermissionScope> scopes, boolean grantableToTenantSystem) {

    public CataloguedPermission {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        scopes = Set.copyOf(scopes);
        if (code.isBlank()) {
            throw new IllegalArgumentException("a permission code must not be blank");
        }
    }

    public boolean validIn(PermissionScope scope) {
        return scopes.contains(scope);
    }
}
