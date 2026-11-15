package dev.ngb.mailroom.audience.application.segment.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import java.util.List;
import java.util.UUID;

/** The segment listing endpoints, read inside a tenant-scoped transaction. */
public class SegmentQueries {

    public static final int DEFAULT_LIMIT = 50;

    private final UnitOfWork unitOfWork;
    private final SegmentReadRepository segments;

    public SegmentQueries(UnitOfWork unitOfWork, SegmentReadRepository segments) {
        this.unitOfWork = unitOfWork;
        this.segments = segments;
    }

    public List<SegmentView> list(UUID after, Integer limit) {
        return unitOfWork.transactional(
                () -> segments.list(after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public SegmentView get(UUID segmentId) {
        return unitOfWork.transactional(() -> segments.find(segmentId)
                .orElseThrow(() -> new NotFoundException("No segment exists here.")));
    }
}
