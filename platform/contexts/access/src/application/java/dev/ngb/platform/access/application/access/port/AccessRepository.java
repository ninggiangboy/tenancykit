package dev.ngb.platform.access.application.access.port;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The joins behind effective access: direct grants plus the codes of every assigned role. */
public interface AccessRepository {

    Set<String> effectivePermissions(UUID userId);

    /** The holders of one role, ascending by id, so a fan-out always takes its user locks in one order. */
    List<UUID> userIdsForRole(UUID roleId);

    void assign(UUID tenantId, UUID userId, UUID roleId);

    void unassign(UUID tenantId, UUID userId, UUID roleId);

    void grant(UUID tenantId, UUID userId, String permissionCode);

    void revoke(UUID tenantId, UUID userId, String permissionCode);

    Set<String> directPermissions(UUID userId);
}
