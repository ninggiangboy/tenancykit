package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.mailroom.audience.domain.receiver.ReceiverStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's audience; row-level security restricts every query to the bound tenant. */
public interface AudienceReadRepository {

    List<ReceiverView> listReceivers(ReceiverStatus status, String contains, UUID afterId, int limit);

    Optional<ReceiverView> findReceiver(UUID receiverId);

    List<GroupView> listGroups(UUID afterId, int limit);

    Optional<GroupView> findGroup(UUID groupId);

    List<ReceiverView> pageGroupReceivers(UUID groupId, UUID afterReceiverId, int limit);

    List<ReceiverView> findReceivers(List<UUID> receiverIds);

    List<SuppressionView> listSuppressions(String afterNormalizedAddress, int limit);
}
