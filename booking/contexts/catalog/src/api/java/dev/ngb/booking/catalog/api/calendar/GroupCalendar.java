package dev.ngb.booking.catalog.api.calendar;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A group's members and the strategy that picks among them. */
public record GroupCalendar(UUID groupId, String strategy, int maxCombine, List<GroupMemberRef> members) {

    public GroupCalendar {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(strategy, "strategy");
        members = List.copyOf(members);
    }
}
