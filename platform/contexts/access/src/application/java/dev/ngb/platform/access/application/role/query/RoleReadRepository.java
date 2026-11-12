package dev.ngb.platform.access.application.role.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleReadRepository {

    List<RoleView> list(UUID tenantId);

    Optional<RoleView> findById(UUID id);
}
