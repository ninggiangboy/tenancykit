package dev.ngb.mailroom.audience.api.audience;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sending context's read seam onto an audience: it snapshots a group or a segment for a campaign.
 *
 * <p>A page is ordered by receiver id, so the caller can resume after the last one it saw. A segment is evaluated
 * here, where the filter and the receivers live, never in the sending context.
 */
public interface AudienceDirectory {

    List<ReceiverSummary> pageGroup(UUID groupId, UUID afterReceiverId, int limit);

    List<ReceiverSummary> pageSegment(UUID segmentId, UUID afterReceiverId, int limit);

    /** Resolves the receivers a campaign snapshot claimed; a deleted receiver is simply absent. */
    List<ReceiverSummary> receiversByIds(List<UUID> receiverIds);
}
