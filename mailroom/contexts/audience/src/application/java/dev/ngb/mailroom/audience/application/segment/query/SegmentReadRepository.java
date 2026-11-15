package dev.ngb.mailroom.audience.application.segment.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's segments. */
public interface SegmentReadRepository {

    List<SegmentView> list(UUID afterId, int limit);

    Optional<SegmentView> find(UUID segmentId);
}
