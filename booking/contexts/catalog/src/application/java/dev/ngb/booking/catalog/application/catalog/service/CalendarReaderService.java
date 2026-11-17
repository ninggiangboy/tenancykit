package dev.ngb.booking.catalog.application.catalog.service;

import dev.ngb.booking.catalog.api.calendar.BlockRange;
import dev.ngb.booking.catalog.api.calendar.CalendarExceptionRange;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.GroupCalendar;
import dev.ngb.booking.catalog.api.calendar.GroupMemberRef;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.OfferingRequirement;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import dev.ngb.booking.catalog.api.calendar.WorkingHourWindow;
import dev.ngb.booking.catalog.application.block.query.BlockReadRepository;
import dev.ngb.booking.catalog.application.block.query.BlockView;
import dev.ngb.booking.catalog.application.calendar.query.CalendarReadRepository;
import dev.ngb.booking.catalog.application.calendar.query.CalendarExceptionView;
import dev.ngb.booking.catalog.application.calendar.query.WorkingHourView;
import dev.ngb.booking.catalog.application.group.query.GroupReadRepository;
import dev.ngb.booking.catalog.application.group.query.GroupView;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.booking.catalog.application.offering.query.OfferingView;
import dev.ngb.booking.catalog.application.offering.query.RequirementView;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.booking.catalog.application.resource.query.ResourceView;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves another context the calendar data it may read. Availability lives in inventory, but the hours, exceptions,
 * blocks and selling rules are the catalog's, so this service is the one door between them.
 */
public class CalendarReaderService implements CalendarReader {

    private final ResourceReadRepository resources;
    private final CalendarReadRepository calendars;
    private final OfferingReadRepository offerings;
    private final GroupReadRepository groups;
    private final BlockReadRepository blocks;
    private final RecurrenceValidator recurrence;

    public CalendarReaderService(
            ResourceReadRepository resources,
            CalendarReadRepository calendars,
            OfferingReadRepository offerings,
            GroupReadRepository groups,
            BlockReadRepository blocks,
            RecurrenceValidator recurrence) {
        this.resources = resources;
        this.calendars = calendars;
        this.offerings = offerings;
        this.groups = groups;
        this.blocks = blocks;
        this.recurrence = recurrence;
    }

    @Override
    public Optional<ResourceCalendar> resource(UUID resourceId) {
        return resources.findById(resourceId).map(this::calendar);
    }

    @Override
    public Optional<OfferingCalendar> offering(UUID offeringId) {
        return offerings.findById(offeringId).map(this::calendar);
    }

    @Override
    public Optional<GroupCalendar> group(UUID groupId) {
        return groups.findById(groupId).map(this::calendar);
    }

    @Override
    public List<BlockRange> blocks(UUID resourceId, Instant from, Instant to) {
        Optional<ResourceView> resource = resources.findById(resourceId);
        if (resource.isEmpty()) {
            return List.of();
        }
        ZoneId zone = ZoneId.of(resource.get().zone());
        List<BlockRange> ranges = new ArrayList<>();
        for (BlockView block : blocks.all()) {
            if (!block.resourceId().equals(resourceId)) {
                continue;
            }
            Duration duration = Duration.between(block.startsAt(), block.endsAt());
            if (block.rule().isEmpty()) {
                addIfOverlapping(ranges, block.startsAt(), block.startsAt().plus(duration), from, to);
                continue;
            }
            String dtstart = block.startsAt().atZone(zone).toLocalDateTime().toString();
            for (Instant start : recurrence.expand(zone.getId(), dtstart, block.rule(), from, to)) {
                addIfOverlapping(ranges, start, start.plus(duration), from, to);
            }
        }
        return List.copyOf(ranges);
    }

    private static void addIfOverlapping(
            List<BlockRange> ranges, Instant start, Instant end, Instant from, Instant to) {
        if (start.isBefore(to) && end.isAfter(from)) {
            ranges.add(new BlockRange(start, end));
        }
    }

    private ResourceCalendar calendar(ResourceView resource) {
        List<WorkingHourWindow> hours = new ArrayList<>();
        for (WorkingHourView hour : calendars.hours(resource.id())) {
            hours.add(new WorkingHourWindow(hour.weekday(), hour.opensMinute(), hour.closesMinute()));
        }
        List<CalendarExceptionRange> exceptions = new ArrayList<>();
        for (CalendarExceptionView exception : calendars.exceptions(resource.id())) {
            List<WorkingHourWindow> windows = new ArrayList<>();
            for (WorkingHourView hour : exception.hours()) {
                windows.add(new WorkingHourWindow(hour.weekday(), hour.opensMinute(), hour.closesMinute()));
            }
            exceptions.add(new CalendarExceptionRange(
                    exception.fromDate(), exception.toDate(), exception.kind(), windows));
        }
        return new ResourceCalendar(resource.id(), resource.zone(), hours, exceptions);
    }

    private OfferingCalendar calendar(OfferingView offering) {
        List<OfferingRequirement> requirements = new ArrayList<>();
        for (RequirementView requirement : offering.requirements()) {
            requirements.add(new OfferingRequirement(
                    requirement.slotCode(), requirement.resourceId(), requirement.groupId()));
        }
        return new OfferingCalendar(
                offering.id(),
                offering.kind().name(),
                offering.durationMin(),
                offering.durationMax(),
                offering.durationStep(),
                offering.slotStep(),
                offering.bufferBefore(),
                offering.bufferAfter(),
                offering.leadTimeSeconds(),
                offering.horizonDays(),
                requirements);
    }

    private GroupCalendar calendar(GroupView group) {
        List<GroupMemberRef> members = new ArrayList<>();
        for (var member : group.members()) {
            members.add(new GroupMemberRef(member.resourceId(), member.priority()));
        }
        return new GroupCalendar(group.id(), group.strategy(), group.maxCombine(), members);
    }
}
