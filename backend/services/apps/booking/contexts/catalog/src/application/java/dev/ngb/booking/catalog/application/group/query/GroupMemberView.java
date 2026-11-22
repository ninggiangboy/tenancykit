package dev.ngb.booking.catalog.application.group.query;

import java.util.UUID;

/** One member of a group as a read returns it: the resource and where it sits for the priority strategy. */
public record GroupMemberView(UUID resourceId, int priority) {}
