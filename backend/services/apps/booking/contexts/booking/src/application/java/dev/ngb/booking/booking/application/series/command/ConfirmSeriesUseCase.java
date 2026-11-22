package dev.ngb.booking.booking.application.series.command;

import dev.ngb.booking.booking.application.booking.command.ConfirmBookingUseCase;
import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.series.port.SeriesRepository;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.booking.booking.domain.series.BookingSeries;
import dev.ngb.booking.booking.domain.series.SeriesStatus;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Confirms every held occurrence of a series in one transaction; one lost hold rolls them all back. */
public class ConfirmSeriesUseCase {

    private final UnitOfWork unitOfWork;
    private final SeriesRepository series;
    private final BookingRepository bookings;
    private final ConfirmBookingUseCase confirms;

    public ConfirmSeriesUseCase(
            UnitOfWork unitOfWork,
            SeriesRepository series,
            BookingRepository bookings,
            ConfirmBookingUseCase confirms) {
        this.unitOfWork = unitOfWork;
        this.series = series;
        this.bookings = bookings;
        this.confirms = confirms;
    }

    public BookingSeries execute(UUID seriesId) {
        return unitOfWork.transactional(() -> {
            BookingSeries stored = series
                    .lockById(seriesId)
                    .orElseThrow(() -> new NotFoundException("No series exists here with that id."));
            if (stored.status() == SeriesStatus.CANCELLED) {
                throw new BookingStateException("The series is cancelled.");
            }
            for (Booking booking : bookings.findBySeriesId(seriesId)) {
                if (booking.status() == BookingStatus.HELD) {
                    confirms.execute(booking.id().value());
                }
            }
            return stored;
        });
    }
}
