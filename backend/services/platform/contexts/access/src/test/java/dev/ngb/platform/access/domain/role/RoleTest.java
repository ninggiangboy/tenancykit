package dev.ngb.platform.access.domain.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Replacing a role's permissions: a custom role bumps its version, a default role refuses, and a no-op does nothing. */
class RoleTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private static Role role(boolean system, Set<String> permissions) {
        return Role.create(new RoleId(UUID.randomUUID()), TENANT, PermissionScope.TENANT, "Operators", system, permissions, NOW);
    }

    @Test
    void aReplacementStartsAtVersionOneAndBumpsItOnce() {
        Role role = role(false, Set.of("billing:invoice:read"));

        assertThat(role.version()).isEqualTo(1);
        assertThat(role.replacePermissions(Set.of("billing:invoice:read", "billing:invoice:write"))).isTrue();

        assertThat(role.permissions()).containsExactlyInAnyOrder("billing:invoice:read", "billing:invoice:write");
        assertThat(role.version()).as("one write, one version").isEqualTo(2);
    }

    @Test
    void theSameSetIsANoOpAndDoesNotBumpTheVersion() {
        Role role = role(false, Set.of("billing:invoice:read"));

        assertThat(role.replacePermissions(Set.of("billing:invoice:read"))).isFalse();

        assertThat(role.version()).isEqualTo(1);
    }

    @Test
    void anEmptyReplacementClearsTheSet() {
        Role role = role(false, Set.of("billing:invoice:read"));

        assertThat(role.replacePermissions(Set.of())).isTrue();

        assertThat(role.permissions()).isEmpty();
        assertThat(role.version()).isEqualTo(2);
    }

    @Test
    void thePermissionsItExposesAreACopy() {
        Role role = role(false, Set.of("billing:invoice:read"));

        assertThatThrownBy(() -> role.permissions().add("billing:invoice:write"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(role.permissions()).containsExactly("billing:invoice:read");
    }

    @Test
    void aDefaultRolesPermissionsCannotBeChanged() {
        Role role = role(true, Set.of("platform:tenant:read"));

        assertThatThrownBy(() -> role.replacePermissions(Set.of("platform:tenant:manage")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(role.version()).isEqualTo(1);
        assertThat(role.permissions()).containsExactly("platform:tenant:read");
    }

    @Test
    void aRenameStripsTheNameAndBumpsTheVersionOnlyWhenItChanges() {
        Role role = role(false, Set.of());

        assertThat(role.rename("  Operators  ")).isFalse();
        assertThat(role.rename("Billing Ops")).isTrue();
        assertThat(role.name()).isEqualTo("Billing Ops");
        assertThat(role.version()).isEqualTo(2);
    }

    @Test
    void aNameMustBePresentAndShortEnough() {
        assertThatThrownBy(() -> role(false, Set.of()).rename("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> role(false, Set.of()).rename("x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRestoredRoleKeepsTheVersionItWasReadWith() {
        Role role = Role.restore(new RoleId(UUID.randomUUID()), TENANT, PermissionScope.PLATFORM, "Admins", false, Set.of(), NOW, 7);

        assertThat(role.version()).isEqualTo(7);
        assertThat(role.scope()).isEqualTo(PermissionScope.PLATFORM);
        assertThat(role.isSystem()).isFalse();
    }
}
