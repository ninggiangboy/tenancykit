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

    Optional<Role> findByName(UUID tenantId, String name);
}
