package dev.ngb.mailroom.audience.application.group.port;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The write side of group membership. Deletes never delete the receivers. */
public interface MembershipRepository {

    /** Idempotent: a receiver already in the group is not added twice. */
    int add(TenantId tenantId, UUID groupId, List<UUID> receiverIds, Instant now);

    int remove(UUID groupId, List<UUID> receiverIds);
}
