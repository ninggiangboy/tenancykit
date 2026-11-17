package dev.ngb.booking.inventory.application.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.ngb.booking.inventory.application.availability.query.AvailabilitySlot;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The free starts of an interval offering, over weekly hours, exceptions, blocks and several requirements. */
class AvailabilityTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
    private static final Instant NOW = MONDAY.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant TO = NOW.plus(Duration.ofDays(1));

    private final AvailabilityCalculator calculator =
            new AvailabilityCalculator((resourceId, from, to) -> List.of());

    @Test
    void weeklyHoursAreRespected() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 0, 0, 0, 365, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars =
                Map.of(resource, calendar(resource, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()));

        assertThat(starts(offering, calendars, Map.of(), Map.of()))
                .containsExactly(at("09:00"), at("10:00"), at("11:00"), at("12:00"), at("13:00"), at("14:00"),
                        at("15:00"), at("16:00"));
    }

    @Test
    void aClosedExceptionRemovesTheDaysSlots() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 0, 0, 0, 365, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars = Map.of(
                resource,
                calendar(
                        resource,
                        List.of(new WorkingHourWindow(1, 540, 1020)),
                        List.of(new CalendarExceptionRange(MONDAY, MONDAY, "CLOSED", List.of()))));

        assertThat(starts(offering, calendars, Map.of(), Map.of())).isEmpty();
    }

    @Test
    void anOpenExceptionReplacesTheDaysHours() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 0, 0, 0, 365, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars = Map.of(
                resource,
                calendar(
                        resource,
                        List.of(new WorkingHourWindow(1, 540, 1020)),
                        List.of(new CalendarExceptionRange(
                                MONDAY, MONDAY, "OPEN", List.of(new WorkingHourWindow(1, 720, 840))))));

        assertThat(starts(offering, calendars, Map.of(), Map.of())).containsExactly(at("12:00"), at("13:00"));
    }

    @Test
    void aBlockRemovesTheSlotsItOverlaps() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 0, 0, 0, 365, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars =
                Map.of(resource, calendar(resource, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()));
        Map<UUID, List<BlockRange>> blocks =
                Map.of(resource, List.of(new BlockRange(at("12:00"), at("13:00"))));

        assertThat(starts(offering, calendars, Map.of(), blocks))
                .contains(at("11:00"), at("13:00"))
                .doesNotContain(at("12:00"));
    }

    @Test
    void theLeadTimeAndHorizonCutTheWindow() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 0, 0, 3600, 1, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars =
                Map.of(resource, calendar(resource, List.of(new WorkingHourWindow(1, 0, 1440)), List.of()));

        List<Instant> starts = starts(
                offering, calendars, Map.of(), Map.of(), NOW, NOW.plus(Duration.ofDays(3)), null);

        assertThat(starts.getFirst()).isEqualTo(at("01:00"));
        assertThat(starts.getLast()).isEqualTo(at("23:00"));
        assertThat(starts.stream().allMatch(start -> !start.isAfter(NOW.plus(Duration.ofDays(1)))))
                .isTrue();
    }

    @Test
    void buffersMayOverhangOpenTime() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 30, 30, 0, 365, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars =
                Map.of(resource, calendar(resource, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()));

        assertThat(starts(offering, calendars, Map.of(), Map.of())).contains(at("09:00"), at("16:00"));
    }

    @Test
    void aBlockOverTheBufferRemovesTheStart() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(60, null, 1, 60, 30, 30, 0, 365, List.of(requirement(resource)));
        Map<UUID, ResourceCalendar> calendars =
                Map.of(resource, calendar(resource, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()));
        Map<UUID, List<BlockRange>> blocks =
                Map.of(resource, List.of(new BlockRange(at("08:31"), at("09:00"))));

        assertThat(starts(offering, calendars, Map.of(), blocks))
                .contains(at("10:00"))
                .doesNotContain(at("09:00"));
    }

    @Test
    void everyRequirementNeedsADistinctFreeResource() {
        UUID group = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        OfferingCalendar offering = interval(
                60,
                null,
                1,
                60,
                0,
                0,
                0,
                365,
                List.of(new OfferingRequirement("one", null, group), new OfferingRequirement("two", null, group)));
        GroupCalendar members = new GroupCalendar(
                group, "PRIORITY", 1, List.of(new GroupMemberRef(first, 0), new GroupMemberRef(second, 0)));
        Map<UUID, GroupCalendar> groups = Map.of(group, members);
        Map<UUID, ResourceCalendar> both = Map.of(
                first,
                calendar(first, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()),
                second,
                calendar(second, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()));

        assertThat(slots(offering, both, groups, Map.of()).getFirst().resourceIds())
                .containsExactlyInAnyOrder(first, second);

        Map<UUID, ResourceCalendar> onlyOne = Map.of(
                first,
                calendar(first, List.of(new WorkingHourWindow(1, 540, 1020)), List.of()),
                second,
                calendar(second, List.of(), List.of()));

        assertThat(starts(offering, onlyOne, groups, Map.of())).isEmpty();
    }

    @Test
    void aDurationOffTheStepIsRefused() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(30, 120, 30, 30, 0, 0, 0, 365, List.of(requirement(resource)));

        assertThatThrownBy(() -> starts(offering, Map.of(), Map.of(), Map.of(), NOW, TO, 45))
                .isInstanceOf(InvalidDurationException.class)
                .hasMessageContaining("duration");
    }

    @Test
    void aDurationOutsideTheRangeIsRefused() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = interval(30, 60, 30, 30, 0, 0, 0, 365, List.of(requirement(resource)));

        assertThatThrownBy(() -> starts(offering, Map.of(), Map.of(), Map.of(), NOW, TO, 90))
                .isInstanceOf(InvalidDurationException.class);
    }

    @Test
    void aCapacityOfferingIsRefused() {
        OfferingCalendar offering = new OfferingCalendar(
                UUID.randomUUID(), "CAPACITY", 60, null, 1, 60, 0, 0, 0, 365, List.of());

        assertThatThrownBy(() -> starts(offering, Map.of(), Map.of(), Map.of()))
                .isInstanceOf(InvalidAvailabilityException.class)
                .hasMessageContaining("INTERVAL");
    }

    private List<AvailabilitySlot> slots(
            OfferingCalendar offering,
            Map<UUID, ResourceCalendar> calendars,
            Map<UUID, GroupCalendar> groups,
            Map<UUID, List<BlockRange>> blocks) {
        return slots(offering, calendars, groups, blocks, NOW, TO, null);
    }

    private List<AvailabilitySlot> slots(
            OfferingCalendar offering,
            Map<UUID, ResourceCalendar> calendars,
            Map<UUID, GroupCalendar> groups,
            Map<UUID, List<BlockRange>> blocks,
            Instant from,
            Instant to,
            Integer durationMinutes) {
        return calculator.freeStarts(new AvailabilityCalculator.Request(
                offering,
                id -> Optional.ofNullable(calendars.get(id)),
                id -> Optional.ofNullable(groups.get(id)),
                id -> blocks.getOrDefault(id, List.of()),
                from,
                to,
                durationMinutes,
                null,
                null,
                NOW));
    }

    private List<Instant> starts(
            OfferingCalendar offering,
            Map<UUID, ResourceCalendar> calendars,
            Map<UUID, GroupCalendar> groups,
            Map<UUID, List<BlockRange>> blocks) {
        return starts(offering, calendars, groups, blocks, NOW, TO, null);
    }

    private List<Instant> starts(
            OfferingCalendar offering,
            Map<UUID, ResourceCalendar> calendars,
            Map<UUID, GroupCalendar> groups,
            Map<UUID, List<BlockRange>> blocks,
            Instant from,
            Instant to,
            Integer durationMinutes) {
        return slots(offering, calendars, groups, blocks, from, to, durationMinutes).stream()
                .map(AvailabilitySlot::start)
                .toList();
    }

    private static OfferingRequirement requirement(UUID resource) {
        return new OfferingRequirement("staff", resource, null);
    }

    private static OfferingCalendar interval(
            int durationMin,
            Integer durationMax,
            int durationStep,
            int slotStep,
            int bufferBefore,
            int bufferAfter,
            int leadTimeSeconds,
            int horizonDays,
            List<OfferingRequirement> requirements) {
        return new OfferingCalendar(
                UUID.randomUUID(),
                "INTERVAL",
                durationMin,
                durationMax,
                durationStep,
                slotStep,
                bufferBefore,
                bufferAfter,
                leadTimeSeconds,
                horizonDays,
                requirements);
    }

    private static ResourceCalendar calendar(
            UUID resource, List<WorkingHourWindow> hours, List<CalendarExceptionRange> exceptions) {
        return new ResourceCalendar(resource, "UTC", hours, exceptions);
    }

    private static Instant at(String time) {
        return MONDAY.atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC);
    }
}
