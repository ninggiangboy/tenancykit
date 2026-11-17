package dev.ngb.booking.booking.adapter.booking.web;

import dev.ngb.booking.booking.application.booking.command.CancelBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.ConfirmBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.CreateBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.ExtendBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.MarkNoShowUseCase;
import dev.ngb.booking.booking.application.booking.command.RescheduleBookingUseCase;
import dev.ngb.booking.booking.application.booking.query.AssignedResourceView;
import dev.ngb.booking.booking.application.booking.query.BookingListing;
import dev.ngb.booking.booking.application.booking.query.BookingQueries;
import dev.ngb.booking.booking.application.booking.query.BookingView;
import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ExpectedVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bookings: holding a place or a range, reading one, and the three transitions that leave the held state —
 * confirming it, lengthening its hold once, or giving it back.
 *
 * <p>A create is safe to repeat: the {@code Idempotency-Key} header makes a retry return the booking the first call
 * wrote, and the same key with another body is refused. The reads carry the version as an {@code ETag}, so a caller
 * knows which state it confirmed or extended.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class BookingController {

    /** A hold as the caller sends it; the offering's kind decides which optional fields are read. */
    public record BookingCreateRequest(
            UUID offeringId,
            String customerRef,
            UUID sessionId,
            String poolCode,
            Integer qty,
            Instant start,
            Integer durationMinutes,
            UUID resourceId,
            Integer partySize) {}

    public record BookingExtendRequest(Integer seconds) {}

    /** A move: the fields the caller omits keep the place the booking already has. */
    public record BookingRescheduleRequest(
            UUID sessionId, String poolCode, Instant start, Integer durationMinutes, UUID resourceId) {}

    public record AssignedResourceResponse(String slotCode, UUID resourceId) {

        static AssignedResourceResponse of(AssignedResource resource) {
            return new AssignedResourceResponse(resource.slotCode(), resource.resourceId());
        }

        static AssignedResourceResponse ofView(AssignedResourceView view) {
            return new AssignedResourceResponse(view.slotCode(), view.resourceId());
        }
    }

    public record BookingResponse(
            String id,
            String status,
            UUID offeringId,
            String offeringKind,
            String customerRef,
            int qty,
            String source,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResourceResponse> resources,
            UUID seriesId,
            Instant holdExpiresAt,
            int extendedSeconds,
            Instant confirmedAt,
            Instant cancelledAt,
            String policyCode,
            long version) {

        public static BookingResponse of(Booking booking) {
            return new BookingResponse(
                    booking.id().toString(),
                    booking.status().name(),
                    booking.offeringId(),
                    booking.offeringKind(),
                    booking.customerRef(),
                    booking.qty(),
                    booking.source().name(),
                    booking.sessionId(),
                    booking.poolCode(),
                    booking.startsAt(),
                    booking.endsAt(),
                    booking.resources().stream()
                            .map(AssignedResourceResponse::of)
                            .toList(),
                    booking.seriesId(),
                    booking.holdExpiresAt(),
                    booking.extendedSeconds(),
                    booking.confirmedAt(),
                    booking.cancelledAt(),
                    booking.policyCode(),
                    booking.version());
        }

        public static BookingResponse ofView(BookingView view) {
            return new BookingResponse(
                    view.id().toString(),
                    view.status(),
                    view.offeringId(),
                    view.offeringKind(),
                    view.customerRef(),
                    view.qty(),
                    view.source(),
                    view.sessionId(),
                    view.poolCode(),
                    view.startsAt(),
                    view.endsAt(),
                    view.resources().stream()
                            .map(AssignedResourceResponse::ofView)
                            .toList(),
                    view.seriesId(),
                    view.holdExpiresAt(),
                    view.extendedSeconds(),
                    view.confirmedAt(),
                    view.cancelledAt(),
                    view.policyCode(),
                    view.version());
        }
    }

    private final CreateBookingUseCase creates;
    private final ConfirmBookingUseCase confirms;
    private final ExtendBookingUseCase extensions;
    private final CancelBookingUseCase cancels;
    private final RescheduleBookingUseCase reschedules;
    private final MarkNoShowUseCase noShows;
    private final BookingQueries bookings;

    public BookingController(
            CreateBookingUseCase creates,
            ConfirmBookingUseCase confirms,
            ExtendBookingUseCase extensions,
            CancelBookingUseCase cancels,
            RescheduleBookingUseCase reschedules,
            MarkNoShowUseCase noShows,
            BookingQueries bookings) {
        this.creates = creates;
        this.confirms = confirms;
        this.extensions = extensions;
        this.cancels = cancels;
        this.reschedules = reschedules;
        this.noShows = noShows;
        this.bookings = bookings;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link BookingListing}. */
    @GetMapping("/bookings")
    @Listing(BookingListing.class)
    @RequiresPermission(BookingPermissions.BOOKING_READ)
    public Page<BookingResponse> listBookings(ListQuery query) {
        return bookings.list(query).map(BookingResponse::ofView);
    }

    /** Holds a place or a range, or replays the booking the same key already created. */
    @PostMapping("/bookings")
    @RepeatableCreate
    @RequiresPermission(BookingPermissions.BOOKING_HOLD)
    public ResponseEntity<BookingResponse> createBooking(
            @RequestHeader(value = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody BookingCreateRequest request) {
        CreateBookingUseCase.Result result = creates.execute(new CreateBookingUseCase.Command(
                request.offeringId(),
                request.customerRef(),
                request.sessionId(),
                request.poolCode(),
                request.qty(),
                request.start(),
                request.durationMinutes(),
                request.resourceId(),
                request.partySize(),
                idempotencyKey,
                null,
                null));
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .eTag(ExpectedVersion.etag(result.booking().version()))
                .body(BookingResponse.of(result.booking()));
    }

    @GetMapping("/bookings/{bookingId}")
    @RequiresPermission(BookingPermissions.BOOKING_READ)
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID bookingId) {
        BookingView booking = bookings.get(bookingId);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(booking.version()))
                .body(BookingResponse.ofView(booking));
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    @RequiresPermission(BookingPermissions.BOOKING_CONFIRM)
    public ResponseEntity<BookingResponse> confirmBooking(@PathVariable UUID bookingId) {
        Booking booking = confirms.execute(bookingId);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(booking.version()))
                .body(BookingResponse.of(booking));
    }

    @PostMapping("/bookings/{bookingId}/extend")
    @RequiresPermission(BookingPermissions.BOOKING_HOLD)
    public ResponseEntity<BookingResponse> extendBooking(
            @PathVariable UUID bookingId, @RequestBody BookingExtendRequest request) {
        Booking booking = extensions.execute(bookingId, request.seconds());
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(booking.version()))
                .body(BookingResponse.of(booking));
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @RequiresPermission(BookingPermissions.BOOKING_HOLD)
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable UUID bookingId) {
        Booking booking = cancels.execute(bookingId);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(booking.version()))
                .body(BookingResponse.of(booking));
    }

    /** Claims the new place and releases the old one in one transaction, keeping the booking id and status. */
    @PostMapping("/bookings/{bookingId}/reschedule")
    @RequiresPermission(BookingPermissions.BOOKING_MANAGE)
    public ResponseEntity<BookingResponse> rescheduleBooking(
            @PathVariable UUID bookingId, @RequestBody BookingRescheduleRequest request) {
        Booking booking = reschedules.execute(
                bookingId,
                new RescheduleBookingUseCase.Command(
                        request.sessionId(),
                        request.poolCode(),
                        request.start(),
                        request.durationMinutes(),
                        request.resourceId()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(booking.version()))
                .body(BookingResponse.of(booking));
    }

    /** Allowed once a confirmed booking's start has passed; the claims stay as history. */
    @PostMapping("/bookings/{bookingId}/no-show")
    @RequiresPermission(BookingPermissions.BOOKING_MANAGE)
    public ResponseEntity<BookingResponse> markNoShow(@PathVariable UUID bookingId) {
        Booking booking = noShows.execute(bookingId);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(booking.version()))
                .body(BookingResponse.of(booking));
    }
}
