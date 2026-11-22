package dev.ngb.booking.catalog.domain.group;

import java.util.Objects;
import java.util.UUID;

/** One resource of a group: the resource it names and where it sits for the {@code PRIORITY} strategy. */
public record GroupMember(UUID resourceId, int priority) {

    public GroupMember {
        Objects.requireNonNull(resourceId, "resourceId");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be at least 0");
        }
    }
}
