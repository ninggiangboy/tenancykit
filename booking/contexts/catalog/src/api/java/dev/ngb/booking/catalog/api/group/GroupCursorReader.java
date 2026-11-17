package dev.ngb.booking.catalog.api.group;

import java.util.Optional;
import java.util.UUID;

/**
 * The round-robin position of one group.
 *
 * <p>The position is the last member the group assigned, so the next booking starts after it rather than always at the
 * first. It belongs to the group because the group is the catalog's row; an assignment advances it in the booking's
 * transaction, which is what makes a rollback leave the rotation where it was.
 */
public interface GroupCursorReader {

    /** @return the member the group assigned last, or empty when it has assigned nobody yet */
    Optional<UUID> current(UUID groupId);

    /** Moves the group's position to a member it just assigned. */
    void advance(UUID groupId, UUID resourceId);
}
