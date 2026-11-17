package dev.ngb.workflow.connections.application.connection.query;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A connection as a read answers it: never the secret, not even its envelope. */
public record ConnectionView(
        UUID id,
        String name,
        String kind,
        String allowedHost,
        Map<String, String> headers,
        String keyId,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
