package dev.ngb.booking.catalog.adapter.group.persistence;

import static dev.ngb.booking_jooq.Tables.RESOURCE_GROUPS;

import dev.ngb.booking.catalog.api.group.GroupCursorReader;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Keeps the round-robin position on the group's own row, so a rotation costs one update in the caller's transaction.
 *
 * <p>A position that is not a UUID, such as one written by a hand-edited row, reads as "nobody assigned yet" rather than
 * failing a booking over a cursor.
 */
public class JooqGroupCursorReader implements GroupCursorReader {

    private final DSLContext dsl;

    public JooqGroupCursorReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<UUID> current(UUID groupId) {
        return dsl.select(RESOURCE_GROUPS.CURSOR)
                .from(RESOURCE_GROUPS)
                .where(RESOURCE_GROUPS.ID.eq(groupId))
                .fetchOptional()
                .flatMap(record -> parse(record.value1()));
    }

    @Override
    public void advance(UUID groupId, UUID resourceId) {
        dsl.update(RESOURCE_GROUPS)
                .set(RESOURCE_GROUPS.CURSOR, resourceId.toString())
                .where(RESOURCE_GROUPS.ID.eq(groupId))
                .execute();
    }

    private static Optional<UUID> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
