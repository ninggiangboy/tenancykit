package dev.ngb.booking.booking.application.series.command;

import dev.ngb.booking.booking.application.booking.command.CancelBookingUseCase;
import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.series.port.SeriesRepository;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.booking.booking.domain.series.BookingSeries;
import dev.ngb.booking.booking.domain.series.BookingSeriesEvents;
import dev.ngb.booking.booking.domain.series.SeriesStatus;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Cancels a series and its future occurrences in one transaction; an occurrence already past is left alone. */
public class CancelSeriesUseCase {

    private final UnitOfWork unitOfWork;
    private final SeriesRepository series;
    private final BookingRepository bookings;
    private final CancelBookingUseCase cancels;
    private final EventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CancelSeriesUseCase(
            UnitOfWork unitOfWork,
            SeriesRepository series,
            BookingRepository bookings,
            CancelBookingUseCase cancels,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.series = series;
        this.bookings = bookings;
        this.cancels = cancels;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public BookingSeries execute(UUID seriesId) {
        return unitOfWork.transactional(() -> {
            BookingSeries stored = series
                    .lockById(seriesId)
                    .orElseThrow(() -> new NotFoundException("No series exists here with that id."));
            if (stored.status() == SeriesStatus.CANCELLED) {
                throw new BookingStateException("The series is already cancelled.");
            }
            Instant now = clock.now();
            int cancelled = 0;
            for (Booking booking : bookings.findBySeriesId(seriesId)) {
                if (booking.startsAt() != null
                        && booking.startsAt().isAfter(now)
                        && (booking.status() == BookingStatus.HELD || booking.status() == BookingStatus.CONFIRMED)) {
                    cancels.execute(booking.id().value());
                    cancelled++;
                }
            }
            stored.cancel(now);
            series.update(stored);
            events.publish(BookingSeriesEvents.cancelled(
                    ids.newId(), tenantContext.requireTenant(), stored, cancelled, now));
            return stored;
        });
    }
}
