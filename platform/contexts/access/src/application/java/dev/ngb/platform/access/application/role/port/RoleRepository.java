package dev.ngb.platform.access.application.role.port;

import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import java.util.Optional;
import java.util.UUID;

/** Write side of roles. */
public interface RoleRepository {

    void add(Role role);

    void update(Role role);

    Optional<Role> findById(RoleId id);

    /** Locks the role so a rename or a permission replacement cannot race another writer. */
    Optional<Role> lockForUpdate(RoleId id);

    /**
     * Locks the role for an assign or unassign. A share lock conflicts with {@link #lockForUpdate}, so an assign and a
     * permission replacement on one role serialise.
     */
    Optional<Role> lockForShare(RoleId id);

    Optional<Role> findByName(UUID tenantId, String name);
}
