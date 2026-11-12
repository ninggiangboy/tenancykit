package dev.ngb.platform.access.application.user.query;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read side of users, the effective access they carry and the replica stream every app consumes. */
public interface UserReadRepository {

    List<UserView> list(UUID tenantId);

    Optional<UserView> findById(UUID id);

    Optional<UserView> findByKeycloakSubject(String subject);

    Set<String> effectivePermissions(UUID userId);

    List<UserAccessView> pageAccess(UUID after, int limit);
}
