package dev.ngb.mailroom.audience.application.audience.query;

import java.time.Instant;
import java.util.UUID;

/** One group as the API shows it. */
public record GroupView(UUID id, String name, long version, Instant createdAt, Instant updatedAt) {}
