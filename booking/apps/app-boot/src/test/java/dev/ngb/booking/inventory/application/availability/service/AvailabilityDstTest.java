package dev.ngb.booking.inventory.application.availability.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.OfferingRequirement;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import dev.ngb.booking.catalog.api.calendar.WorkingHourWindow;
import dev.ngb.booking.inventory.application.availability.query.AvailabilitySlot;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A day with a spring-forward: the missing hour offers no start, and the intended local hours are kept. */
class AvailabilityDstTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static final LocalDate SUNDAY = LocalDate.of(2026, 3, 8);

    private static final Instant NOW = Instant.parse("2026-03-08T00:00:00Z");

    private final AvailabilityCalculator calculator =
            new AvailabilityCalculator((resourceId, from, to) -> List.of());

    @Test
    void aSpringForwardDayOffersNoStartInTheGapAndKeepsTheHours() {
        UUID resource = UUID.randomUUID();
        OfferingCalendar offering = new OfferingCalendar(
                UUID.randomUUID(),
                "INTERVAL",
                60,
                null,
                1,
                30,
                0,
                0,
                0,
                365,
                null,
                1,
                List.of(new OfferingRequirement("staff", resource, null)));
        ResourceCalendar calendar = new ResourceCalendar(
                resource, NEW_YORK.getId(), List.of(new WorkingHourWindow(7, 60, 300)), List.of());

        List<Instant> starts = calculator
                .freeStarts(new AvailabilityCalculator.Request(
                        offering,
                        id -> id.equals(resource) ? Optional.of(calendar) : Optional.empty(),
                        id -> Optional.empty(),
                        id -> List.of(),
                        NOW,
                        NOW.plus(Duration.ofDays(1)),
                        null,
                        null,
                        null,
                        NOW))
                .stream()
                .map(AvailabilitySlot::start)
                .toList();

        assertThat(starts)
                .containsExactly(
                        Instant.parse("2026-03-08T06:00:00Z"),
                        Instant.parse("2026-03-08T06:30:00Z"),
                        Instant.parse("2026-03-08T07:00:00Z"),
                        Instant.parse("2026-03-08T07:30:00Z"),
                        Instant.parse("2026-03-08T08:00:00Z"));
        assertThat(starts.stream().noneMatch(start -> start.atZone(NEW_YORK).getHour() == 2))
                .isTrue();
        assertThat(starts.getFirst().atZone(NEW_YORK).toLocalDate()).isEqualTo(SUNDAY);
    }
}
