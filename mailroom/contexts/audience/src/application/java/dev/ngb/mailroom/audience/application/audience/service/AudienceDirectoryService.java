package dev.ngb.mailroom.audience.application.audience.service;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.audience.api.audience.AudienceDirectory;
import dev.ngb.mailroom.audience.api.audience.ReceiverSummary;
import dev.ngb.mailroom.audience.application.audience.query.AudienceReadRepository;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverView;
import dev.ngb.mailroom.audience.application.segment.port.SegmentMatcher;
import dev.ngb.mailroom.audience.application.segment.port.SegmentRepository;
import dev.ngb.mailroom.audience.domain.segment.Segment;
import java.util.List;
import java.util.UUID;

/** Pages a campaign's audience: a static group directly, a segment through the compiled filter. */
public class AudienceDirectoryService implements AudienceDirectory {

    private final UnitOfWork unitOfWork;
    private final AudienceReadRepository audience;
    private final SegmentRepository segments;
    private final SegmentMatcher matcher;

    public AudienceDirectoryService(
            UnitOfWork unitOfWork,
            AudienceReadRepository audience,
            SegmentRepository segments,
            SegmentMatcher matcher) {
        this.unitOfWork = unitOfWork;
        this.audience = audience;
        this.segments = segments;
        this.matcher = matcher;
    }

    @Override
    public List<ReceiverSummary> pageGroup(UUID groupId, UUID afterReceiverId, int limit) {
        return unitOfWork.transactional(() -> audience.pageGroupReceivers(groupId, afterReceiverId, limit).stream()
                .map(AudienceDirectoryService::summary)
                .toList());
    }

    @Override
    public List<ReceiverSummary> pageSegment(UUID segmentId, UUID afterReceiverId, int limit) {
        return unitOfWork.transactional(() -> {
            Segment segment = segments.findById(segmentId)
                    .orElseThrow(() -> new NotFoundException("No segment exists here."));
            return matcher.slice(segment.tenantId(), segment.filter(), afterReceiverId, limit);
        });
    }

    @Override
    public List<ReceiverSummary> receiversByIds(List<UUID> receiverIds) {
        return unitOfWork.transactional(() -> audience.findReceivers(receiverIds).stream()
                .map(AudienceDirectoryService::summary)
                .toList());
    }

    private static ReceiverSummary summary(ReceiverView view) {
        return new ReceiverSummary(
                view.id(), view.email(), view.firstName(), view.lastName(), view.attributes());
    }
}
