package dev.ngb.mailroom.audience.application.segment.query;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One segment as the API shows it. */
public record SegmentView(
        UUID id, String name, Map<String, Object> filter, long version, Instant createdAt, Instant updatedAt) {}
