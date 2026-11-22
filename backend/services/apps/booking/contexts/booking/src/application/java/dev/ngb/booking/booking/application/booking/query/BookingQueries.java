package dev.ngb.booking.booking.application.booking.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads the current tenant's bookings for the GET handlers. */
public class BookingQueries {

    private final UnitOfWork unitOfWork;
    private final BookingReadRepository bookings;

    public BookingQueries(UnitOfWork unitOfWork, BookingReadRepository bookings) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
    }

    public Page<BookingView> list(ListQuery query) {
        return unitOfWork.transactional(() -> bookings.list(query));
    }

    /** @throws NotFoundException when no booking of this tenant has the id */
    public BookingView get(UUID bookingId) {
        return unitOfWork.transactional(() -> bookings
                .findById(bookingId)
                .orElseThrow(() -> new NotFoundException("No booking exists here with that id.")));
    }
}
