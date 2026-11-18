package dev.ngb.booking.inventory.application.availability.service;

import dev.ngb.booking.catalog.api.calendar.BlockRange;
import dev.ngb.booking.catalog.api.calendar.CalendarExceptionRange;
import dev.ngb.booking.catalog.api.calendar.GroupCalendar;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import dev.ngb.booking.catalog.api.calendar.WorkingHourWindow;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner.RequirementOptions;
import dev.ngb.booking.inventory.api.availability.BusyRange;
import dev.ngb.booking.inventory.api.availability.BusyRangeReader;
import dev.ngb.booking.inventory.application.availability.exception.InvalidAvailabilityException;
import dev.ngb.booking.inventory.application.availability.exception.InvalidDurationException;
import dev.ngb.booking.inventory.application.availability.query.AvailabilitySlot;
import dev.ngb.core.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final ResourceAssignmentPlanner planner;

    public AvailabilityCalculator(BusyRangeReader busyRanges, ResourceAssignmentPlanner planner) {
        this.busyRanges = busyRanges;
        this.planner = planner;
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
        if (offering.requirements().isEmpty()) {
            return List.of();
        }
        Instant first = later(request.from(), request.now().plusSeconds(offering.leadTimeSeconds()));
        Instant last = earlier(request.to(), request.now().plus(Duration.ofDays(offering.horizonDays())));
        if (last.isBefore(first)) {
            return List.of();
        }
        List<RequirementOptions> candidates = planner.options(plannerRequest(request, first, duration));
        if (candidates.isEmpty()) {
            if (request.resource() != null) {
                throw new NotFoundException("No resource exists here with that id.");
            }
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
            List<RequirementOptions> candidates,
            Map<UUID, ResourceAvailability> resources,
            Request request,
            long bufferBefore,
            long bufferAfter) {
        Instant bufferedStart = start.minusSeconds(bufferBefore);
        Instant bufferedEnd = end.plusSeconds(bufferAfter);
        List<UUID> chosen = new ArrayList<>();
        if (!choose(
                0,
                candidates,
                new HashSet<>(),
                chosen,
                start,
                end,
                bufferedStart,
                bufferedEnd,
                resources,
                request)) {
            return null;
        }
        return List.copyOf(chosen);
    }

    /**
     * Takes the first option of each requirement that is free and not already taken, backing out when a later
     * requirement cannot be filled. The option order is the group's strategy order, so the answer is what a booking
     * would choose, and an option may name several resources when seats are combined.
     */
    private boolean choose(
            int index,
            List<RequirementOptions> candidates,
            Set<UUID> used,
            List<UUID> chosen,
            Instant start,
            Instant end,
            Instant bufferedStart,
            Instant bufferedEnd,
            Map<UUID, ResourceAvailability> resources,
            Request request) {
        if (index == candidates.size()) {
            return true;
        }
        for (List<UUID> option : candidates.get(index).options()) {
            boolean usable = true;
            for (UUID resourceId : option) {
                if (used.contains(resourceId)
                        || !free(resourceId, start, end, bufferedStart, bufferedEnd, resources, request)) {
                    usable = false;
                    break;
                }
            }
            if (!usable) {
                continue;
            }
            used.addAll(option);
            chosen.addAll(option);
            if (choose(
                    index + 1,
                    candidates,
                    used,
                    chosen,
                    start,
                    end,
                    bufferedStart,
                    bufferedEnd,
                    resources,
                    request)) {
                return true;
            }
            for (int removed = 0; removed < option.size(); removed++) {
                chosen.remove(chosen.size() - 1);
            }
            used.removeAll(option);
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

    private static ResourceAssignmentPlanner.Request plannerRequest(Request request, Instant start, int duration) {
        return new ResourceAssignmentPlanner.Request(
                request.offering(),
                start,
                start.plusSeconds(duration * (long) SECONDS_PER_MINUTE),
                request.offering().bufferBefore() * (long) SECONDS_PER_MINUTE,
                request.offering().bufferAfter() * (long) SECONDS_PER_MINUTE,
                request.resource(),
                request.partySize(),
                request.calendars(),
                request.groups(),
                request.blocks());
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
