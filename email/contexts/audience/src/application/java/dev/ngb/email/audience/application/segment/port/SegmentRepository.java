package dev.ngb.email.audience.application.segment.port;

import dev.ngb.email.audience.domain.segment.Segment;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's saved segments. */
public interface SegmentRepository {

    void insert(Segment segment);

    boolean update(Segment segment, long expectedVersion);

    boolean delete(UUID segmentId);

    Optional<Segment> findById(UUID segmentId);
}
