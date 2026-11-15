package dev.ngb.email.audience.domain.segment;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A saved filter over receiver attributes, evaluated when a campaign snapshots its audience. */
public record Segment(
        UUID id,
        TenantId tenantId,
        String name,
        Map<String, Object> filter,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME = 200;

    public Segment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        filter = SegmentFilter.validate(filter);
        if (version < 1) {
            throw new IllegalArgumentException("a segment starts at version 1");
        }
    }

    public static Segment create(UUID id, TenantId tenantId, String name, Map<String, Object> filter, Instant now) {
        return new Segment(id, tenantId, name, filter, 1, now, now);
    }

    public Segment revise(String name, Map<String, Object> filter, Instant now) {
        return new Segment(id, tenantId, name, filter, version + 1, createdAt, now);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a segment needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("a segment name is at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }
}
