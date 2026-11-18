package dev.ngb.mailroom.audience.application.segment.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.mailroom.audience.application.segment.port.SegmentRepository;
import dev.ngb.mailroom.audience.domain.segment.Segment;
import java.util.Map;
import java.util.UUID;

/** Replaces a segment's name and filter, guarded by {@code If-Match}. */
public class UpdateSegmentUseCase {

    private final UnitOfWork unitOfWork;
    private final SegmentRepository segments;
    private final Clock clock;

    public UpdateSegmentUseCase(UnitOfWork unitOfWork, SegmentRepository segments, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.segments = segments;
        this.clock = clock;
    }

    public Segment execute(UUID segmentId, String name, Map<String, Object> filter, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Segment current = segments.findById(segmentId)
                    .orElseThrow(() -> new NotFoundException("No segment exists here."));
            expected.check(current.version());
            Segment updated = current.revise(name, filter, clock.now());
            if (!segments.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
