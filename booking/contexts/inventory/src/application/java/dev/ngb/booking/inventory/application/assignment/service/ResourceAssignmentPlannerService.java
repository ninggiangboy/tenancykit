package dev.ngb.booking.inventory.application.assignment.service;

import dev.ngb.booking.catalog.api.calendar.BlockRange;
import dev.ngb.booking.catalog.api.calendar.GroupCalendar;
import dev.ngb.booking.catalog.api.calendar.GroupMemberRef;
import dev.ngb.booking.catalog.api.calendar.OfferingRequirement;
import dev.ngb.booking.catalog.api.calendar.OpenHours;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import dev.ngb.booking.catalog.api.group.GroupCursorReader;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner;
import dev.ngb.booking.inventory.api.availability.BusyRange;
import dev.ngb.booking.inventory.api.availability.BusyRangeReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fills an offering's requirements by each group's strategy, then refuses a choice that is not open, blocked or claimed.
 *
 * <p>The options are ordered by the strategy themselves, and the assignments keep that order, so the caller tries the
 * group's preferred member first. A seat-limited group is the one strategy that may fill a requirement with several
 * resources: one suitable member when there is one, otherwise the smallest-waste combination inside {@code maxCombine}.
 *
 * <p>The search is a depth-first walk over the requirements and is bounded twice: groups hold at most twenty members
 * and a caller is answered with at most {@value #MAX_ASSIGNMENTS} complete assignments. Distinctness is enforced while
 * walking, so a resource chosen for one requirement is never offered to another.
 */
public class ResourceAssignmentPlannerService implements ResourceAssignmentPlanner {

    private static final int MAX_ASSIGNMENTS = 100;
    private static final int MAX_COMBINATIONS = 50;
    private static final int MAX_GENERATED_COMBINATIONS = 5_000;
    private static final int SECONDS_PER_MINUTE = 60;

    private static final String PRIORITY = "PRIORITY";
    private static final String ROUND_ROBIN = "ROUND_ROBIN";
    private static final String LEAST_BUSY = "LEAST_BUSY";
    private static final String FIT_SEATS = "FIT_SEATS";

    private final BusyRangeReader busy;
    private final GroupCursorReader cursors;

    public ResourceAssignmentPlannerService(BusyRangeReader busy, GroupCursorReader cursors) {
        this.busy = busy;
        this.cursors = cursors;
    }

    @Override
    public List<RequirementOptions> options(Request request) {
        List<RequirementOptions> all = new ArrayList<>();
        UUID pinned = request.pinnedResource();
        for (OfferingRequirement requirement : request.offering().requirements()) {
            if (requirement.resourceId() != null) {
                if (pinned != null && !pinned.equals(requirement.resourceId())) {
                    continue;
                }
                all.add(new RequirementOptions(
                        requirement.slotCode(), null, null, List.of(List.of(requirement.resourceId()))));
                continue;
            }
            GroupCalendar group = request.groups().apply(requirement.groupId()).orElse(null);
            if (group == null) {
                all.add(new RequirementOptions(requirement.slotCode(), requirement.groupId(), null, List.of()));
                continue;
            }
            if (pinned != null) {
                if (group.members().stream().noneMatch(member -> member.resourceId().equals(pinned))) {
                    continue;
                }
                all.add(new RequirementOptions(
                        requirement.slotCode(), group.groupId(), group.strategy(), List.of(List.of(pinned))));
                continue;
            }
            all.add(new RequirementOptions(
                    requirement.slotCode(), group.groupId(), group.strategy(), groupOptions(group, request)));
        }
        return List.copyOf(all);
    }

    @Override
    public List<Assignment> assignments(Request request) {
        List<RequirementOptions> requirements = options(request);
        if (requirements.isEmpty() || requirements.stream().anyMatch(requirement -> requirement.options().isEmpty())) {
            return List.of();
        }
        Probe probe = new Probe(request);
        List<Assignment> assignments = new ArrayList<>();
        search(requirements, 0, new LinkedHashSet<>(), new ArrayList<>(), probe, assignments);
        return List.copyOf(assignments);
    }

    private void search(
            List<RequirementOptions> requirements,
            int index,
            Set<UUID> used,
            List<Slot> slots,
            Probe probe,
            List<Assignment> assignments) {
        if (assignments.size() >= MAX_ASSIGNMENTS) {
            return;
        }
        if (index == requirements.size()) {
            assignments.add(new Assignment(slots));
            return;
        }
        RequirementOptions requirement = requirements.get(index);
        for (List<UUID> option : requirement.options()) {
            if (option.stream().anyMatch(used::contains)) {
                continue;
            }
            boolean available = true;
            for (UUID resourceId : option) {
                if (!probe.free(resourceId)) {
                    available = false;
                    break;
                }
            }
            if (!available) {
                continue;
            }
            used.addAll(option);
            slots.add(new Slot(requirement.slotCode(), requirement.groupId(), requirement.strategy(), option));
            search(requirements, index + 1, used, slots, probe, assignments);
            slots.remove(slots.size() - 1);
            used.removeAll(option);
            if (assignments.size() >= MAX_ASSIGNMENTS) {
                return;
            }
        }
    }

    private List<List<UUID>> groupOptions(GroupCalendar group, Request request) {
        if (group.members().isEmpty()) {
            return List.of();
        }
        return switch (group.strategy() == null ? PRIORITY : group.strategy()) {
            case ROUND_ROBIN -> rotation(group, request);
            case LEAST_BUSY -> leastBusy(group, request);
            case FIT_SEATS -> fitSeats(group, request);
            default -> byPriority(group.members());
        };
    }

    private static List<List<UUID>> byPriority(List<GroupMemberRef> members) {
        List<GroupMemberRef> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(GroupMemberRef::priority).thenComparing(GroupMemberRef::resourceId));
        List<List<UUID>> options = new ArrayList<>(ordered.size());
        for (GroupMemberRef member : ordered) {
            options.add(List.of(member.resourceId()));
        }
        return options;
    }

    private List<List<UUID>> rotation(GroupCalendar group, Request request) {
        List<GroupMemberRef> ordered = new ArrayList<>(group.members());
        ordered.sort(Comparator.comparingInt(GroupMemberRef::priority).thenComparing(GroupMemberRef::resourceId));
        Optional<UUID> cursor = cursors.current(group.groupId());
        if (cursor.isEmpty()) {
            return single(ordered);
        }
        int next = 0;
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).resourceId().equals(cursor.get())) {
                next = index + 1;
                break;
            }
        }
        List<GroupMemberRef> rotated = new ArrayList<>(ordered.size());
        for (int offset = 0; offset < ordered.size(); offset++) {
            rotated.add(ordered.get((next + offset) % ordered.size()));
        }
        return single(rotated);
    }

    private static List<List<UUID>> single(List<GroupMemberRef> members) {
        List<List<UUID>> options = new ArrayList<>(members.size());
        for (GroupMemberRef member : members) {
            options.add(List.of(member.resourceId()));
        }
        return options;
    }

    private List<List<UUID>> leastBusy(GroupCalendar group, Request request) {
        Map<UUID, Long> minutes = new HashMap<>();
        for (GroupMemberRef member : group.members()) {
            minutes.put(member.resourceId(), busyMinutes(member.resourceId(), request));
        }
        List<GroupMemberRef> ordered = new ArrayList<>(group.members());
        ordered.sort(Comparator.comparingLong((GroupMemberRef member) -> minutes.get(member.resourceId()))
                .thenComparingInt(GroupMemberRef::priority)
                .thenComparing(GroupMemberRef::resourceId));
        return single(ordered);
    }

    private long busyMinutes(UUID resourceId, Request request) {
        ZoneId zone = zone(resourceId, request);
        LocalDate day = request.start().atZone(zone).toLocalDate();
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        long seconds = 0;
        for (BusyRange range : busy.busy(resourceId, from, to)) {
            Instant start = range.start().isBefore(from) ? from : range.start();
            Instant end = range.end().isAfter(to) ? to : range.end();
            if (start.isBefore(end)) {
                seconds += Duration.between(start, end).getSeconds();
            }
        }
        return seconds / SECONDS_PER_MINUTE;
    }

    private ZoneId zone(UUID resourceId, Request request) {
        return request.calendars()
                .apply(resourceId)
                .map(calendar -> ZoneId.of(calendar.zone()))
                .orElse(ZoneOffset.UTC);
    }

    private List<List<UUID>> fitSeats(GroupCalendar group, Request request) {
        int party = request.partySize() == null ? 1 : Math.max(1, request.partySize());
        Map<UUID, Integer> seats = new HashMap<>();
        for (GroupMemberRef member : group.members()) {
            seats.put(member.resourceId(), seats(member.resourceId(), request));
        }
        Map<UUID, Integer> priorities = new HashMap<>();
        for (GroupMemberRef member : group.members()) {
            priorities.put(member.resourceId(), member.priority());
        }
        List<GroupMemberRef> sufficient = new ArrayList<>();
        List<GroupMemberRef> limited = new ArrayList<>();
        for (GroupMemberRef member : group.members()) {
            Integer count = seats.get(member.resourceId());
            if (count == null || count >= party) {
                sufficient.add(member);
            }
            if (count != null) {
                limited.add(member);
            }
        }
        sufficient.sort(Comparator.comparingInt(
                                (GroupMemberRef member) -> {
                                    Integer count = seats.get(member.resourceId());
                                    return count == null ? Integer.MAX_VALUE : count;
                                })
                        .thenComparingInt(GroupMemberRef::priority)
                        .thenComparing(GroupMemberRef::resourceId));
        if (!sufficient.isEmpty()) {
            return single(sufficient);
        }
        int maxCombine = Math.max(1, group.maxCombine());
        if (maxCombine < 2 || limited.size() < 2) {
            return List.of();
        }
        limited.sort(Comparator.comparingInt(GroupMemberRef::priority).thenComparing(GroupMemberRef::resourceId));
        List<List<UUID>> combinations = new ArrayList<>();
        combine(limited, seats, party, maxCombine, 0, new ArrayList<>(), combinations);
        combinations.sort(Comparator.comparingInt((List<UUID> option) -> waste(option, seats, party))
                .thenComparingInt(List::size)
                .thenComparing(ResourceAssignmentPlannerService::compareIds));
        return combinations.size() > MAX_COMBINATIONS
                ? List.copyOf(combinations.subList(0, MAX_COMBINATIONS))
                : combinations;
    }

    private static void combine(
            List<GroupMemberRef> members,
            Map<UUID, Integer> seats,
            int party,
            int maxCombine,
            int start,
            List<UUID> chosen,
            List<List<UUID>> combinations) {
        if (combinations.size() >= MAX_GENERATED_COMBINATIONS) {
            return;
        }
        if (chosen.size() >= 2 && seats(chosen, seats) >= party) {
            combinations.add(List.copyOf(chosen));
        }
        if (chosen.size() == maxCombine) {
            return;
        }
        for (int index = start; index < members.size(); index++) {
            chosen.add(members.get(index).resourceId());
            combine(members, seats, party, maxCombine, index + 1, chosen, combinations);
            chosen.remove(chosen.size() - 1);
        }
    }

    private static int seats(List<UUID> resources, Map<UUID, Integer> seats) {
        int total = 0;
        for (UUID resourceId : resources) {
            total += seats.getOrDefault(resourceId, 0);
        }
        return total;
    }

    private static int waste(List<UUID> option, Map<UUID, Integer> seats, int party) {
        return Math.max(0, seats(option, seats) - party);
    }

    private static int compareIds(List<UUID> left, List<UUID> right) {
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            int compared = left.get(index).compareTo(right.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private Integer seats(UUID resourceId, Request request) {
        return request.calendars().apply(resourceId).map(ResourceCalendar::seats).orElse(null);
    }

    /** The open, unblocked and unclaimed question, cached per resource so one assignment walk reads each resource once. */
    private final class Probe {

        private final Request request;
        private final Instant bufferedStart;
        private final Instant bufferedEnd;
        private final Map<UUID, Boolean> answers = new HashMap<>();
        private final Set<UUID> checked = new HashSet<>();

        private Probe(Request request) {
            this.request = request;
            this.bufferedStart = request.start().minusSeconds(request.bufferBeforeSeconds());
            this.bufferedEnd = request.end().plusSeconds(request.bufferAfterSeconds());
        }

        private boolean free(UUID resourceId) {
            if (checked.add(resourceId)) {
                answers.put(resourceId, check(resourceId));
            }
            return answers.get(resourceId);
        }

        private boolean check(UUID resourceId) {
            Optional<ResourceCalendar> calendar = request.calendars().apply(resourceId);
            if (calendar.isEmpty() || !OpenHours.covers(calendar.get(), request.start(), request.end())) {
                return false;
            }
            for (BlockRange block : request.blocks().apply(resourceId)) {
                if (overlaps(bufferedStart, bufferedEnd, block.startsAt(), block.endsAt())) {
                    return false;
                }
            }
            for (BusyRange range : busy.busy(resourceId, bufferedStart, bufferedEnd)) {
                if (overlaps(bufferedStart, bufferedEnd, range.start(), range.end())) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
}
