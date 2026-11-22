package dev.ngb.booking.booking.application.series.command;

import dev.ngb.booking.booking.application.booking.command.CreateBookingUseCase;
import dev.ngb.booking.booking.application.booking.exception.BookingProblem;
import dev.ngb.booking.booking.application.booking.exception.OutsideOpenHoursException;
import dev.ngb.booking.booking.application.booking.exception.SoldOutException;
import dev.ngb.booking.booking.application.booking.service.BookingRules;
import dev.ngb.booking.booking.application.series.exception.InvalidSeriesException;
import dev.ngb.booking.booking.application.series.exception.SeriesConflictException;
import dev.ngb.booking.booking.application.series.port.SeriesRepository;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.booking.domain.series.BookingSeries;
import dev.ngb.booking.booking.domain.series.BookingSeriesEvents;
import dev.ngb.booking.booking.domain.series.SeriesMode;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.recurrence.RecurrenceException;
import dev.ngb.booking.catalog.api.recurrence.RecurrenceReader;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Books a repeating series: one hold per occurrence, sharing a recurrence and a hold expiry.
 *
 * <p>In {@code ALL_OR_NOTHING} every occurrence is created in one transaction; a refusal anywhere lists every failing
 * occurrence and rolls all of them back, so the caller never has half a series. In {@code SKIP_CONFLICTS} each
 * occurrence is its own transaction and the ones the calendar refuses are reported alongside the ones that became
 * holds.
 *
 * <p>The interval is expanded from the local start the Tenant sent, in its zone, so the same wall-clock time is booked
 * across a DST change. A rule that expands past {@value #MAX_OCCURRENCES} occurrences is refused rather than silently
 * truncated.
 */
public class CreateSeriesUseCase {

    private static final int MAX_OCCURRENCES = 52;
    private static final long EXPANSION_DAYS = 365L * 5;
    private static final String INTERVAL = "INTERVAL";

    public record Command(
            UUID offeringId,
            String customerRef,
            String zone,
            String dtstart,
            List<String> rule,
            Integer durationMinutes,
            String poolCode,
            SeriesMode mode) {}

    public record Result(
            BookingSeries series,
            List<Booking> bookings,
            List<SeriesConflictException.Conflict> skipped,
            int occurrenceCount) {}

    private final UnitOfWork unitOfWork;
    private final SeriesRepository series;
    private final CreateBookingUseCase creates;
    private final BookingRules rules;
    private final CalendarReader calendars;
    private final RecurrenceReader recurrence;
    private final EventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateSeriesUseCase(
            UnitOfWork unitOfWork,
            SeriesRepository series,
            CreateBookingUseCase creates,
            BookingRules rules,
            CalendarReader calendars,
            RecurrenceReader recurrence,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.series = series;
        this.creates = creates;
        this.rules = rules;
        this.calendars = calendars;
        this.recurrence = recurrence;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        Plan plan = unitOfWork.transactional(() -> plan(command));
        return plan.series().mode() == SeriesMode.SKIP_CONFLICTS ? bookSkipping(plan) : bookAll(plan);
    }

    private Plan plan(Command command) {
        OfferingCalendar offering = calendars
                .offering(command.offeringId())
                .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
        if (!INTERVAL.equals(offering.kind())) {
            throw new InvalidSeriesException("A series books an INTERVAL offering.");
        }
        String customerRef = rules.customerRef(command.customerRef());
        Instant first = firstOccurrence(command.zone(), command.dtstart());
        List<Instant> starts;
        try {
            starts = recurrence.expand(
                    command.zone(), command.dtstart(), command.rule(), first, first.plusSeconds(
                            EXPANSION_DAYS * 86_400L));
        } catch (RecurrenceException invalid) {
            throw new InvalidSeriesException(invalid.getMessage());
        }
        if (starts.isEmpty()) {
            throw new InvalidSeriesException("The recurrence expands to no occurrence.");
        }
        if (starts.size() > MAX_OCCURRENCES) {
            throw new InvalidSeriesException(
                    "A series holds at most " + MAX_OCCURRENCES + " occurrences and this rule has "
                            + starts.size() + ".");
        }
        Instant now = clock.now();
        BookingSeries series = BookingSeries.create(
                ids.newId(),
                tenantContext.requireTenant(),
                command.offeringId(),
                customerRef,
                command.zone(),
                command.dtstart(),
                command.rule(),
                command.mode() == null ? SeriesMode.ALL_OR_NOTHING : command.mode(),
                command.durationMinutes(),
                command.poolCode(),
                now);
        return new Plan(series, command.durationMinutes(), starts);
    }

    private Result bookAll(Plan plan) {
        return unitOfWork.transactional(() -> {
            series.insert(plan.series());
            List<Booking> bookings = new ArrayList<>(plan.starts().size());
            List<SeriesConflictException.Conflict> conflicts = new ArrayList<>();
            for (Instant start : plan.starts()) {
                try {
                    bookings.add(create(plan, start));
                } catch (BookingProblem refused) {
                    conflicts.add(new SeriesConflictException.Conflict(start, reason(refused)));
                }
            }
            if (!conflicts.isEmpty()) {
                throw new SeriesConflictException(
                        "The series cannot be booked because " + conflicts.size() + " occurrence(s) conflict.",
                        conflicts);
            }
            announce(plan, bookings);
            return new Result(plan.series(), List.copyOf(bookings), List.of(), plan.starts().size());
        });
    }

    private Result bookSkipping(Plan plan) {
        unitOfWork.transactional(() -> {
            series.insert(plan.series());
            return null;
        });
        List<Booking> bookings = new ArrayList<>(plan.starts().size());
        List<SeriesConflictException.Conflict> skipped = new ArrayList<>();
        for (Instant start : plan.starts()) {
            try {
                bookings.add(unitOfWork.transactional(() -> create(plan, start)));
            } catch (BookingProblem refused) {
                skipped.add(new SeriesConflictException.Conflict(start, reason(refused)));
            }
        }
        unitOfWork.transactional(() -> {
            announce(plan, bookings);
            return null;
        });
        return new Result(plan.series(), List.copyOf(bookings), List.copyOf(skipped), plan.starts().size());
    }

    private Booking create(Plan plan, Instant start) {
        return creates
                .execute(new CreateBookingUseCase.Command(
                        plan.series().offeringId(),
                        plan.series().customerRef(),
                        null,
                        null,
                        null,
                        start,
                        plan.durationMinutes(),
                        null,
                        null,
                        null,
                        BookingSource.TENANT,
                        plan.series().id(),
                        null))
                .booking();
    }

    private void announce(Plan plan, List<Booking> bookings) {
        Instant first = bookings.isEmpty() ? plan.starts().get(0) : bookings.get(0).startsAt();
        Instant last = bookings.isEmpty()
                ? plan.starts().get(plan.starts().size() - 1)
                : bookings.get(bookings.size() - 1).endsAt();
        events.publish(BookingSeriesEvents.created(
                ids.newId(),
                tenantContext.requireTenant(),
                plan.series(),
                plan.starts().size(),
                bookings.size(),
                first,
                last,
                clock.now()));
    }

    private static Instant firstOccurrence(String zone, String dtstart) {
        try {
            return LocalDateTime.parse(dtstart).atZone(ZoneId.of(zone)).toInstant();
        } catch (DateTimeException | NullPointerException invalid) {
            throw new InvalidSeriesException("The zone or the local start is not readable.");
        }
    }

    private static String reason(BookingProblem problem) {
        if (problem instanceof OutsideOpenHoursException) {
            return "outside-open-hours";
        }
        if (problem instanceof SoldOutException) {
            return "sold-out";
        }
        return "slot-taken";
    }

    private record Plan(BookingSeries series, Integer durationMinutes, List<Instant> starts) {}
}
