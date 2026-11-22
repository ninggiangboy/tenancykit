package dev.ngb.survey.platform.api.replica;

import dev.ngb.core.id.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The replica's user read seam. Each query filters by tenant explicitly; the replica tables are Platform-owned data
 * and carry no tenant row-level security, so the filter is the isolation guarantee and the tests assert it.
 */
public interface UserDirectory {

    /** Every {@code ACTIVE} tenant-scope user of one tenant. */
    List<UserView> activeUsersOf(TenantId tenantId);

    /** {@code ACTIVE} tenant-scope users of one tenant holding the code. */
    List<UserView> usersWithPermission(TenantId tenantId, String code);

    /** One user by id, or empty when the replica has never seen it. */
    Optional<UserView> user(UUID userId);
}
