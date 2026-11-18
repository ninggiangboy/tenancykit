package dev.ngb.platform.access.domain.user;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import java.time.Instant;
import java.util.Objects;

/** A person who signs in through Keycloak. Platform keeps the identity, its scope and its access version. */
public final class User {

    private final UserId id;
    private final TenantId tenantId;
    private final PermissionScope scope;
    private final String keycloakSubject;
    private final String email;
    private final Instant createdAt;
    private String displayName;
    private UserStatus status;
    private long version;

    private User(
            UserId id, TenantId tenantId, PermissionScope scope, String keycloakSubject, String email, String displayName,
            UserStatus status, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.keycloakSubject = Objects.requireNonNull(keycloakSubject, "keycloakSubject");
        this.email = Objects.requireNonNull(email, "email");
        this.displayName = requireDisplayName(displayName);
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    public static User create(
            UserId id, TenantId tenantId, PermissionScope scope, String keycloakSubject, String email, String displayName, Instant now) {
        return new User(id, tenantId, scope, keycloakSubject, email, displayName, UserStatus.ACTIVE, now, 1);
    }

    public static User restore(
            UserId id, TenantId tenantId, PermissionScope scope, String keycloakSubject, String email, String displayName,
            UserStatus status, Instant createdAt, long version) {
        return new User(id, tenantId, scope, keycloakSubject, email, displayName, status, createdAt, version);
    }

    /** @return false when the name is unchanged, so no event is published for a no-op */
    public boolean rename(String newName) {
        String normalized = requireDisplayName(newName);
        if (normalized.equals(displayName)) {
            return false;
        }
        displayName = normalized;
        version++;
        return true;
    }

    public boolean enable() {
        if (status == UserStatus.ACTIVE) {
            return false;
        }
        status = UserStatus.ACTIVE;
        version++;
        return true;
    }

    public boolean disable() {
        if (status == UserStatus.DISABLED) {
            return false;
        }
        status = UserStatus.DISABLED;
        version++;
        return true;
    }

    /**
     * Records that the user's effective access changed even though the user row itself did not (a role was assigned
     * or edited). The version is what makes a consumer keep the newest state and drop a stale one.
     */
    public void accessChanged() {
        version++;
    }

    public UserId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PermissionScope scope() {
        return scope;
    }

    public String keycloakSubject() {
        return keycloakSubject;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public UserStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    private static String requireDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("display name must be 1-200 characters");
        }
        return displayName.strip();
    }
}
