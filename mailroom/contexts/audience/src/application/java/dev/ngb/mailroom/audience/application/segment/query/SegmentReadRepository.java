package dev.ngb.mailroom.audience.application.segment.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's segments. */
public interface SegmentReadRepository {

    Page<SegmentView> list(ListQuery query);

    Optional<SegmentView> find(UUID segmentId);
}
