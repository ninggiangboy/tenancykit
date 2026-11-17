package dev.ngb.booking.booking.application.booking.port;

import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.Booking;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the bookings of the current tenant and the resources they took. Every statement is tenant-scoped by row-level
 * security, not by a condition here.
 *
 * <p>A booking that is being changed is always read through {@link #lockById}, because the transition that decides
 * whether it may be confirmed, extended or cancelled has to be decided under the row lock: two confirmations that read
 * the same version and both write would otherwise both report a change.
 */
public interface BookingRepository {

    /** The booking and its resources, held under a row lock for the rest of the caller's transaction. */
    Optional<Booking> lockById(UUID bookingId);

    /**
     * Inserts the booking row when the idempotency key it carries has not been used here yet.
     *
     * @return true when this call inserted the row; false when the key already exists, which is a replay
     */
    boolean insertIfAbsent(Booking booking);

    /** The booking a key created, whether it is still held or not. */
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    void update(Booking booking);

    void insertResources(UUID bookingId, List<AssignedResource> resources);

    /** Replaces every resource a booking names, which a reschedule does after it claims the new place. */
    void replaceResources(UUID bookingId, List<AssignedResource> resources);

    /** Every booking of one series, oldest start first, including the ones that left HELD. */
    List<Booking> findBySeriesId(UUID seriesId);

    /** Every resource the booking claimed, ordered by slot code. */
    List<AssignedResource> resourcesOf(UUID bookingId);

    /**
     * The held bookings whose hold lapsed before {@code cutoff}, oldest expiry first, locked so another worker skips
     * them rather than waiting.
     *
     * <p>The caller passes {@code now - grace}: a hold is reclaimed once its grace has run out, not the instant it
     * expires, so a confirmation that arrives just late still finds its place.
     */
    List<Booking> lockExpired(Instant cutoff, int limit);
}
