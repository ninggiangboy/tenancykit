package dev.ngb.survey.platform.application.platform.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.survey.platform.api.replica.UserView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The replica rows the user directory reads. Every query filters by tenant explicitly. */
public interface UserRows {

    List<UserView> activeUsersOf(TenantId tenantId);

    List<UserView> activeUsersWithPermission(TenantId tenantId, String code);

    Optional<UserView> user(UUID userId);
}
