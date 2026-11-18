package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's audience; row-level security restricts every query to the bound tenant. */
public interface AudienceReadRepository {

    Page<ReceiverView> listReceivers(ListQuery query);

    Optional<ReceiverView> findReceiver(UUID receiverId);

    Page<GroupView> listGroups(ListQuery query);

    Optional<GroupView> findGroup(UUID groupId);

    Page<ReceiverView> listGroupReceivers(UUID groupId, ListQuery query);

    /** Keyset page of a group's receivers, oldest id first, for the sender's own iteration. */
    List<ReceiverView> pageGroupReceivers(UUID groupId, UUID afterReceiverId, int limit);

    List<ReceiverView> findReceivers(List<UUID> receiverIds);

    Page<SuppressionView> listSuppressions(ListQuery query);
}
