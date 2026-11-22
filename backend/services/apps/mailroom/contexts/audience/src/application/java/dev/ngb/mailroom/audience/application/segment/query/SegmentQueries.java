package dev.ngb.mailroom.audience.application.segment.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The segment listing endpoints, read inside a tenant-scoped transaction. */
public class SegmentQueries {

    private final UnitOfWork unitOfWork;
    private final SegmentReadRepository segments;

    public SegmentQueries(UnitOfWork unitOfWork, SegmentReadRepository segments) {
        this.unitOfWork = unitOfWork;
        this.segments = segments;
    }

    public Page<SegmentView> list(ListQuery query) {
        return unitOfWork.transactional(() -> segments.list(query));
    }

    public SegmentView get(UUID segmentId) {
        return unitOfWork.transactional(() -> segments.find(segmentId)
                .orElseThrow(() -> new NotFoundException("No segment exists here.")));
    }
}
