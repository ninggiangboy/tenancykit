package dev.ngb.booking.inventory.application.availability.service;

import dev.ngb.booking.catalog.api.calendar.BlockRange;
import dev.ngb.booking.catalog.api.calendar.CalendarExceptionRange;
import dev.ngb.booking.catalog.api.calendar.GroupCalendar;
import dev.ngb.booking.catalog.api.calendar.GroupMemberRef;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.OfferingRequirement;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import dev.ngb.booking.catalog.api.calendar.WorkingHourWindow;
import dev.ngb.booking.inventory.application.availability.exception.InvalidAvailabilityException;
import dev.ngb.booking.inventory.application.availability.exception.InvalidDurationException;
import dev.ngb.booking.inventory.application.availability.port.BusyRange;
import dev.ngb.booking.inventory.application.availability.port.BusyRangeReader;
import dev.ngb.booking.inventory.application.availability.query.AvailabilitySlot;
import dev.ngb.core.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The free start instants of an interval offering, computed at query time and never materialized.
 *
 * <p>A start is on the offering's slot grid, inside an open window resolved per local date in the resource's own zone,
 * at or after the lead time and at or before the horizon, and free of a block or a live claim over the buffered range.
 * A start is offered only when every requirement can be filled by a free resource and no two requirements share one,
 * so the answer is the resource a booking would take, not just the time. Times are instants: a day with 23 or 25 local
 * hours is correct because the windows are resolved, not added.
 */
public class AvailabilityCalculator {

    private static final String INTERVAL = "INTERVAL";
    private static final String CLOSED = "CLOSED";
    private static final int MINUTES_PER_DAY = 1440;
    private static final int SECONDS_PER_MINUTE = 60;

    private final BusyRangeReader busyRanges;

    public AvailabilityCalculator(BusyRangeReader busyRanges) {
        this.busyRanges = busyRanges;
    }

    /** Everything a computation reads: the offering, the calendars and blocks of its resources, the busy ranges. */
    public record Request(
            OfferingCalendar offering,
            Function<UUID, Optional<ResourceCalendar>> calendars,
            Function<UUID, Optional<GroupCalendar>> groups,
            Function<UUID, List<BlockRange>> blocks,
            Instant from,
            Instant to,
            Integer durationMinutes,
            UUID resource,
            Integer partySize,
            Instant now) {}

    /** @return the ascending, distinct free starts whose requirements fit, with the resource of each requirement */
    public List<AvailabilitySlot> freeStarts(Request request) {
        OfferingCalendar offering = request.offering();
        if (!INTERVAL.equals(offering.kind())) {
            throw new InvalidAvailabilityException(
                    "VALIDATION_FAILED", "Availability is computed for an INTERVAL offering.");
        }
        int duration = duration(offering, request.durationMinutes());
        List<OfferingRequirement> requirements = offering.requirements();
        if (requirements.isEmpty()) {
            return List.of();
        }
        List<List<UUID>> candidates = requirementCandidates(requirements, request);
        Instant first = later(request.from(), request.now().plusSeconds(offering.leadTimeSeconds()));
        Instant last = earlier(request.to(), request.now().plus(Duration.ofDays(offering.horizonDays())));
        if (last.isBefore(first)) {
            return List.of();
        }
        long step = offering.slotStep() * (long) SECONDS_PER_MINUTE;
        long bufferBefore = offering.bufferBefore() * (long) SECONDS_PER_MINUTE;
        long bufferAfter = offering.bufferAfter() * (long) SECONDS_PER_MINUTE;
        Map<UUID, ResourceAvailability> resources = new HashMap<>();
        List<AvailabilitySlot> slots = new ArrayList<>();
        Instant candidate = firstOnGrid(first, step);
        while (!candidate.isAfter(last)) {
            Instant serviceEnd = candidate.plusSeconds(duration * (long) SECONDS_PER_MINUTE);
            List<UUID> assignment =
                    assign(candidate, serviceEnd, candidates, resources, request, bufferBefore, bufferAfter);
            if (assignment != null) {
                slots.add(new AvailabilitySlot(candidate, serviceEnd, assignment));
            }
            candidate = candidate.plusSeconds(step);
        }
        return slots;
    }

    /** @return the resource each requirement takes, in requirement order, or null when no distinct choice exists */
    private List<UUID> assign(
            Instant start,
            Instant end,
            List<List<UUID>> candidates,
            Map<UUID, ResourceAvailability> resources,
            Request request,
            long bufferBefore,
            long bufferAfter) {
        Instant bufferedStart = start.minusSeconds(bufferBefore);
        Instant bufferedEnd = end.plusSeconds(bufferAfter);
        Map<UUID, Integer> indexOf = new LinkedHashMap<>();
        for (List<UUID> list : candidates) {
            for (UUID id : list) {
                if (!indexOf.containsKey(id)) {
                    indexOf.put(id, indexOf.size());
                }
            }
        }
        List<UUID> resourceIds = new ArrayList<>(indexOf.keySet());
        int requirements = candidates.size();
        List<List<Integer>> graph = new ArrayList<>(requirements);
        for (List<UUID> list : candidates) {
            List<Integer> free = new ArrayList<>();
            for (UUID id : list) {
                if (free(id, start, end, bufferedStart, bufferedEnd, resources, request)) {
                    free.add(indexOf.get(id));
                }
            }
            graph.add(free);
        }
        int[] resourceMatch = new int[resourceIds.size()];
        int[] requirementMatch = new int[requirements];
        Arrays.fill(resourceMatch, -1);
        Arrays.fill(requirementMatch, -1);
        for (int requirement = 0; requirement < requirements; requirement++) {
            if (!augment(requirement, graph, new boolean[resourceIds.size()], resourceMatch, requirementMatch)) {
                return null;
            }
        }
        List<UUID> assignment = new ArrayList<>(requirements);
        for (int requirement = 0; requirement < requirements; requirement++) {
            assignment.add(resourceIds.get(requirementMatch[requirement]));
        }
        return assignment;
    }

    /** Kuhn's augmentation: a requirement takes a free resource, displacing the one that holds it if it can move. */
    private static boolean augment(
            int requirement,
            List<List<Integer>> graph,
            boolean[] visited,
            int[] resourceMatch,
            int[] requirementMatch) {
        for (int resource : graph.get(requirement)) {
            if (visited[resource]) {
                continue;
            }
            visited[resource] = true;
            if (resourceMatch[resource] == -1
                    || augment(resourceMatch[resource], graph, visited, resourceMatch, requirementMatch)) {
                resourceMatch[resource] = requirement;
                requirementMatch[requirement] = resource;
                return true;
            }
        }
        return false;
    }

    private boolean free(
            UUID resourceId,
            Instant start,
            Instant end,
            Instant bufferedStart,
            Instant bufferedEnd,
            Map<UUID, ResourceAvailability> resources,
            Request request) {
        ResourceAvailability availability = resources.computeIfAbsent(resourceId, id -> load(id, request));
        return availability.fits(start, end) && !availability.blocked(bufferedStart, bufferedEnd);
    }

    private ResourceAvailability load(UUID resourceId, Request request) {
        Optional<ResourceCalendar> calendar = request.calendars().apply(resourceId);
        if (calendar.isEmpty()) {
            return ResourceAvailability.none();
        }
        return new ResourceAvailability(
                openIntervals(calendar.get(), request.from(), request.to()),
                request.blocks().apply(resourceId),
                busyRanges.busy(resourceId, request.from(), request.to()));
    }

    private static List<List<UUID>> requirementCandidates(List<OfferingRequirement> requirements, Request request) {
        List<List<UUID>> candidates = new ArrayList<>(requirements.size());
        UUID pinned = request.resource();
        for (OfferingRequirement requirement : requirements) {
            List<UUID> of = requirement.resourceId() != null
                    ? List.of(requirement.resourceId())
                    : groupMembers(requirement.groupId(), request);
            if (pinned != null && !of.contains(pinned)) {
                of = List.of();
            } else if (pinned != null) {
                of = List.of(pinned);
            }
            candidates.add(of);
        }
        if (pinned != null && candidates.stream().allMatch(List::isEmpty)) {
            throw new NotFoundException("No resource exists here with that id.");
        }
        return candidates;
    }

    private static List<UUID> groupMembers(UUID groupId, Request request) {
        Optional<GroupCalendar> group = request.groups().apply(groupId);
        if (group.isEmpty()) {
            return List.of();
        }
        List<UUID> members = new ArrayList<>();
        for (GroupMemberRef member : group.get().members()) {
            members.add(member.resourceId());
        }
        return members;
    }

    private static int duration(OfferingCalendar offering, Integer requested) {
        int min = offering.durationMin();
        int max = offering.durationMax() == null ? min : offering.durationMax();
        int duration = requested == null ? min : requested;
        if (duration < min || duration > max) {
            throw new InvalidDurationException("The duration must be between " + min + " and " + max + " minutes.");
        }
        int step = offering.durationStep();
        if (step < 1 || Math.floorMod(duration - min, step) != 0) {
            throw new InvalidDurationException("The duration is not on the offering's duration step.");
        }
        return duration;
    }

    private static Instant firstOnGrid(Instant first, long stepSeconds) {
        long seconds = first.getEpochSecond();
        if (first.getNano() > 0) {
            seconds++;
        }
        long remainder = Math.floorMod(seconds, stepSeconds);
        return Instant.ofEpochSecond(remainder == 0 ? seconds : seconds + (stepSeconds - remainder));
    }

    private static List<OpenInterval> openIntervals(ResourceCalendar calendar, Instant from, Instant to) {
        ZoneId zone = ZoneId.of(calendar.zone());
        LocalDate firstDate = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate lastDate = to.atZone(zone).toLocalDate().plusDays(1);
        List<OpenInterval> intervals = new ArrayList<>();
        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            for (WorkingHourWindow window : windows(calendar, date)) {
                Instant opens = instantAt(date, window.opensMinute(), zone);
                Instant closes = instantAt(date, window.closesMinute(), zone);
                if (closes.isAfter(opens)) {
                    intervals.add(new OpenInterval(opens, closes));
                }
            }
        }
        intervals.sort(Comparator.comparing(OpenInterval::start));
        return intervals;
    }

    private static List<WorkingHourWindow> windows(ResourceCalendar calendar, LocalDate date) {
        for (CalendarExceptionRange exception : calendar.exceptions()) {
            if (date.isBefore(exception.fromDate()) || date.isAfter(exception.toDate())) {
                continue;
            }
            return CLOSED.equals(exception.kind()) ? List.of() : exception.hours();
        }
        int weekday = date.getDayOfWeek().getValue();
        List<WorkingHourWindow> weekly = new ArrayList<>();
        for (WorkingHourWindow window : calendar.hours()) {
            if (window.weekday() == weekday) {
                weekly.add(window);
            }
        }
        return weekly;
    }

    private static Instant instantAt(LocalDate date, int minute, ZoneId zone) {
        LocalDate day = minute >= MINUTES_PER_DAY ? date.plusDays(1) : date;
        int fromMidnight = minute % MINUTES_PER_DAY;
        return ZonedDateTime.of(day, LocalTime.of(fromMidnight / 60, fromMidnight % 60), zone).toInstant();
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private record OpenInterval(Instant start, Instant end) {}

    private record ResourceAvailability(List<OpenInterval> open, List<BlockRange> blocks, List<BusyRange> busy) {

        static ResourceAvailability none() {
            return new ResourceAvailability(List.of(), List.of(), List.of());
        }

        boolean fits(Instant start, Instant end) {
            for (OpenInterval interval : open) {
                if (!start.isBefore(interval.start()) && !end.isAfter(interval.end())) {
                    return true;
                }
            }
            return false;
        }

        boolean blocked(Instant start, Instant end) {
            for (BlockRange block : blocks) {
                if (overlaps(start, end, block.startsAt(), block.endsAt())) {
                    return true;
                }
            }
            for (BusyRange range : busy) {
                if (overlaps(start, end, range.start(), range.end())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
            return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
        }
    }
}
