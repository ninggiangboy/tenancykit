package dev.ngb.mailroom.audience.application.segment.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.audience.api.audience.ReceiverSummary;
import dev.ngb.mailroom.audience.application.segment.port.SegmentMatcher;
import dev.ngb.mailroom.audience.application.segment.port.SegmentRepository;
import dev.ngb.mailroom.audience.domain.segment.Segment;
import java.util.List;
import java.util.UUID;

/** Answers "how many, and a sample" without loading the whole audience. */
public class PreviewSegmentUseCase {

    public static final long COUNT_CAP = 10_000;
    private static final int SAMPLE = 5;

    public record Preview(long count, boolean capped, List<ReceiverSummary> sample) {}

    private final UnitOfWork unitOfWork;
    private final SegmentRepository segments;
    private final SegmentMatcher matcher;

    public PreviewSegmentUseCase(
            UnitOfWork unitOfWork, SegmentRepository segments, SegmentMatcher matcher) {
        this.unitOfWork = unitOfWork;
        this.segments = segments;
        this.matcher = matcher;
    }

    public Preview execute(UUID segmentId) {
        return unitOfWork.transactional(() -> {
            Segment segment = segments.findById(segmentId)
                    .orElseThrow(() -> new NotFoundException("No segment exists here."));
            long count = matcher.count(segment.tenantId(), segment.filter());
            List<ReceiverSummary> sample = matcher.slice(segment.tenantId(), segment.filter(), null, SAMPLE);
            return new Preview(Math.min(count, COUNT_CAP), count > COUNT_CAP, sample);
        });
    }
}
