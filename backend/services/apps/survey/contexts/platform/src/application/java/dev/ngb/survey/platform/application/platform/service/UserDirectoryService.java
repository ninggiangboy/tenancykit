package dev.ngb.survey.platform.application.platform.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.survey.platform.api.replica.UserDirectory;
import dev.ngb.survey.platform.api.replica.UserView;
import dev.ngb.survey.platform.application.platform.port.UserRows;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Serves another context from the replica. The class name and package are what {@code api-implementations-are-services} requires. */
public class UserDirectoryService implements UserDirectory {

    private final UserRows rows;

    public UserDirectoryService(UserRows rows) {
        this.rows = rows;
    }

    @Override
    public List<UserView> activeUsersOf(TenantId tenantId) {
        return rows.activeUsersOf(tenantId);
    }

    @Override
    public List<UserView> usersWithPermission(TenantId tenantId, String code) {
        return rows.activeUsersWithPermission(tenantId, code);
    }

    @Override
    public Optional<UserView> user(UUID userId) {
        return rows.user(userId);
    }
}
