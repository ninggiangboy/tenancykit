package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.mailroom.audience.domain.receiver.ReceiverStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One receiver as the API shows it. */
public record ReceiverView(
        UUID id,
        String email,
        String firstName,
        String lastName,
        Map<String, Object> attributes,
        ReceiverStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
