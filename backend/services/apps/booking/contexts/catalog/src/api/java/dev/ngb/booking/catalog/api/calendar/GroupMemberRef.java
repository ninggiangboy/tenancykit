package dev.ngb.booking.catalog.api.calendar;

import java.util.UUID;

/** One group member: the resource and where it sits for the priority strategy. */
public record GroupMemberRef(UUID resourceId, int priority) {}
