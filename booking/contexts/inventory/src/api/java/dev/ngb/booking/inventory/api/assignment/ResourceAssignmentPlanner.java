package dev.ngb.booking.inventory.api.assignment;

import dev.ngb.booking.catalog.api.calendar.BlockRange;
import dev.ngb.booking.catalog.api.calendar.GroupCalendar;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * How a booking fills every requirement of an offering with a distinct resource.
 *
 * <p>A requirement names a resource or a group; a group's strategy ranks its members (by priority, in rotation, by load,
 * or by fitting a party's seats), and a seat-limited group may combine members for one requirement. The planner answers
 * with ordered options and with complete assignments, so a caller tries the group's preferred choice first and can move
 * to the next when the database refuses a claim.
 *
 * <p>Distinctness is a property of a complete assignment: one booking never uses the same resource for two
 * requirements, which is what makes a staff-plus-room hold atomic.
 */
public interface ResourceAssignmentPlanner {

    /** Every requirement and the ordered options that can fill it, without checking whether a resource is free. */
    List<RequirementOptions> options(Request request);

    /** The complete distinct assignments that are open and unclaimed, in the order the caller should try them. */
    List<Assignment> assignments(Request request);

    record Request(
            OfferingCalendar offering,
            Instant start,
            Instant end,
            long bufferBeforeSeconds,
            long bufferAfterSeconds,
            UUID pinnedResource,
            Integer partySize,
            Function<UUID, Optional<ResourceCalendar>> calendars,
            Function<UUID, Optional<GroupCalendar>> groups,
            Function<UUID, List<BlockRange>> blocks) {

        public Request {
            Objects.requireNonNull(offering, "offering");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(calendars, "calendars");
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(blocks, "blocks");
        }
    }

    /** One requirement's slot code and the options that fill it; an option may name several resources to combine. */
    record RequirementOptions(String slotCode, UUID groupId, String strategy, List<List<UUID>> options) {

        public RequirementOptions {
            Objects.requireNonNull(slotCode, "slotCode");
            options = List.copyOf(options);
        }
    }

    record Assignment(List<Slot> slots) {

        public Assignment {
            slots = List.copyOf(slots);
        }
    }

    record Slot(String slotCode, UUID groupId, String strategy, List<UUID> resourceIds) {

        public Slot {
            Objects.requireNonNull(slotCode, "slotCode");
            resourceIds = List.copyOf(resourceIds);
        }
    }
}
