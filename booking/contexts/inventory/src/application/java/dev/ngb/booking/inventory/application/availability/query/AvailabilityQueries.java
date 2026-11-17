package dev.ngb.booking.inventory.application.availability.query;

import dev.ngb.booking.catalog.api.calendar.BlockRange;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.inventory.application.availability.service.AvailabilityCalculator;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads an interval offering's free starts for the GET handler: the offering's selling rules and each resource's
 * calendar come from the catalog's port, the claims come from {@link AvailabilityCalculator}, and the whole read runs
 * in one tenant-scoped transaction so nothing sees another tenant's rows.
 */
public class AvailabilityQueries {

    private final UnitOfWork unitOfWork;
    private final CalendarReader calendars;
    private final Clock clock;
    private final AvailabilityCalculator calculator;

    public AvailabilityQueries(
            UnitOfWork unitOfWork, CalendarReader calendars, Clock clock, AvailabilityCalculator calculator) {
        this.unitOfWork = unitOfWork;
        this.calendars = calendars;
        this.clock = clock;
        this.calculator = calculator;
    }

    /** @throws NotFoundException when the offering does not exist here, or a pinned resource is not a candidate */
    public List<AvailabilitySlot> availability(
            UUID offeringId, Instant from, Instant to, Integer durationMinutes, UUID resource, Integer partySize) {
        return unitOfWork.transactional(() -> {
            OfferingCalendar offering = calendars
                    .offering(offeringId)
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            Function<UUID, List<BlockRange>> blocks = resourceId -> calendars.blocks(resourceId, from, to);
            return calculator.freeStarts(new AvailabilityCalculator.Request(
                    offering,
                    calendars::resource,
                    calendars::group,
                    blocks,
                    from,
                    to,
                    durationMinutes,
                    resource,
                    partySize,
                    clock.now()));
        });
    }
}
