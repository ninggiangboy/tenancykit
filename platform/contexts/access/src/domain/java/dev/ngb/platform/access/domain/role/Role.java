package dev.ngb.platform.access.domain.role;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A customisable group of permission codes. The catalog decides which codes are valid in the role's scope; this
 * aggregate stores the codes and bumps its version whenever the set changes.
 */
public final class Role {

    private final RoleId id;
    private final TenantId tenantId;
    private final PermissionScope scope;
    private final boolean system;
    private final Instant createdAt;
    private String name;
    private final Set<String> permissions;
    private long version;

    private Role(
            RoleId id, TenantId tenantId, PermissionScope scope, String name, boolean system, Set<String> permissions,
            Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.name = requireName(name);
        this.system = system;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.permissions = new HashSet<>(permissions);
        this.version = version;
    }

    public static Role create(
            RoleId id, TenantId tenantId, PermissionScope scope, String name, boolean system, Set<String> permissions, Instant now) {
        return new Role(id, tenantId, scope, name, system, permissions, now, 1);
    }

    public static Role restore(
            RoleId id, TenantId tenantId, PermissionScope scope, String name, boolean system, Set<String> permissions,
            Instant createdAt, long version) {
        return new Role(id, tenantId, scope, name, system, permissions, createdAt, version);
    }

    /** Replaces the permission set; a default role keeps the permissions it ships with. */
    public boolean replacePermissions(Set<String> replacement) {
        if (system) {
            throw new IllegalStateException("a default role's permissions cannot be changed");
        }
        if (permissions.equals(replacement)) {
            return false;
        }
        permissions.clear();
        permissions.addAll(replacement);
        version++;
        return true;
    }

    public boolean rename(String newName) {
        String normalized = requireName(newName);
        if (normalized.equals(name)) {
            return false;
        }
        name = normalized;
        version++;
        return true;
    }

    public RoleId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PermissionScope scope() {
        return scope;
    }

    public String name() {
        return name;
    }

    public boolean isSystem() {
        return system;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Set<String> permissions() {
        return Set.copyOf(permissions);
    }

    public long version() {
        return version;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("role name must be 1-100 characters");
        }
        return name.strip();
    }
}
