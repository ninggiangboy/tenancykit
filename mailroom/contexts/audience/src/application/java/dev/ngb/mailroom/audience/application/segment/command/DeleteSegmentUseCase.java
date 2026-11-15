package dev.ngb.mailroom.audience.application.segment.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.mailroom.audience.application.segment.port.SegmentRepository;
import dev.ngb.mailroom.audience.domain.segment.Segment;
import java.util.UUID;

/** Deletes a saved segment. A campaign that already snapshotted one keeps its recipients. */
public class DeleteSegmentUseCase {

    private final UnitOfWork unitOfWork;
    private final SegmentRepository segments;

    public DeleteSegmentUseCase(UnitOfWork unitOfWork, SegmentRepository segments) {
        this.unitOfWork = unitOfWork;
        this.segments = segments;
    }

    public void execute(UUID segmentId, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            Segment current = segments.findById(segmentId)
                    .orElseThrow(() -> new NotFoundException("No segment exists here."));
            expected.check(current.version());
            if (!segments.delete(segmentId)) {
                throw new PreconditionFailedException(null);
            }
            return null;
        });
    }
}
