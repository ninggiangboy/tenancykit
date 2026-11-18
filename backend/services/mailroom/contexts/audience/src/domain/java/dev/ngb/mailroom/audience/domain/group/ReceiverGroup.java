package dev.ngb.mailroom.audience.domain.group;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A static list of receivers a campaign can target. Deleting it never deletes the receivers. */
public record ReceiverGroup(
        UUID id, TenantId tenantId, String name, long version, Instant createdAt, Instant updatedAt) {

    public static final int MAX_NAME = 200;

    public ReceiverGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        if (version < 1) {
            throw new IllegalArgumentException("a group starts at version 1");
        }
    }

    public static ReceiverGroup create(UUID id, TenantId tenantId, String name, Instant now) {
        return new ReceiverGroup(id, tenantId, name, 1, now, now);
    }

    public ReceiverGroup rename(String name, Instant now) {
        return new ReceiverGroup(id, tenantId, name, version + 1, createdAt, now);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a group needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("a group name is at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }
}
