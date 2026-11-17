package dev.ngb.booking.booking.application.booking.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads bookings straight into their views; the aggregate is never rebuilt for a read. A page's resources are read in
 * one statement for the whole page rather than one per booking.
 */
public interface BookingReadRepository {

    Page<BookingView> list(ListQuery query);

    Optional<BookingView> findById(UUID bookingId);
}
