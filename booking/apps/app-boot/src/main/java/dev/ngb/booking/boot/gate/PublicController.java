package dev.ngb.booking.boot.gate;

import dev.ngb.booking.booking.application.booking.command.CancelBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.CreateBookingUseCase;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.waitlist.command.JoinWaitlistUseCase;
import dev.ngb.booking.booking.application.waitlist.command.LeaveWaitlistUseCase;
import dev.ngb.booking.booking.application.waitlist.query.WaitlistQueries;
import dev.ngb.booking.booking.application.waitlist.query.WaitlistView;
import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.booking.catalog.application.offering.query.OfferingQueries;
import dev.ngb.booking.catalog.application.offering.query.OfferingView;
import dev.ngb.booking.gate.adapter.publicgate.web.GateAttributes;
import dev.ngb.booking.gate.application.service.AdmissionControl;
import dev.ngb.booking.gate.application.exception.GateException;
import dev.ngb.booking.gate.application.service.GateLimits;
import dev.ngb.booking.gate.domain.token.CustomerClaims;
import dev.ngb.booking.gate.domain.token.GateScope;
import dev.ngb.booking.inventory.application.availability.query.AvailabilityQueries;
import dev.ngb.booking.inventory.application.availability.query.AvailabilitySlot;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thin public surface: a customer's browser may read an offering and its free starts, hold with its own reference,
 * release that hold, and use the waitlist. There is deliberately no confirm, extend, reschedule or read of another
 * customer's data here; every route takes its customer from the verified token, never from the body.
 */
@Profile("api")
@RestController
@RequestMapping("/p")
public class PublicController {

    public record PublicOffering(
            UUID id,
            String name,
            String kind,
            Integer durationMin,
            Integer durationMax,
            Integer durationStep,
            Integer remaining) {}

    public record PublicAvailabilitySlot(Instant start, Instant end, List<UUID> resourceIds) {}

    public record PublicAvailability(List<PublicAvailabilitySlot> data) {}

    public record PublicBookingCreate(
            UUID offeringId,
            UUID sessionId,
            String poolCode,
            Integer qty,
            Instant start,
            Integer durationMinutes,
            UUID resourceId,
            Integer partySize) {}

    public record AssignedResourceResponse(String slotCode, UUID resourceId) {}

    public record PublicBooking(
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

        static PublicBooking of(Booking booking) {
            return new PublicBooking(
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
                            .map(assigned -> new AssignedResourceResponse(
                                    assigned.slotCode(), assigned.resourceId()))
                            .toList(),
                    booking.seriesId(),
                    booking.holdExpiresAt(),
                    booking.extendedSeconds(),
                    booking.confirmedAt(),
                    booking.cancelledAt(),
                    booking.policyCode(),
                    booking.version());
        }
    }

    public record PublicWaitlistJoin(
            UUID offeringId,
            Integer qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            UUID resourceId) {}

    public record PublicWaitlistEntry(
            UUID id,
            UUID offeringId,
            String customerRef,
            int qty,
            String status,
            int position,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            UUID offeredBookingId,
            Instant offeredUntil,
            Instant createdAt) {

        static PublicWaitlistEntry of(WaitlistView view) {
            return new PublicWaitlistEntry(
                    view.id(),
                    view.offeringId(),
                    view.customerRef(),
                    view.qty(),
                    view.status(),
                    view.position(),
                    view.sessionId(),
                    view.poolCode(),
                    view.startsAt(),
                    view.endsAt(),
                    view.offeredBookingId(),
                    view.offeredUntil(),
                    view.createdAt());
        }
    }

    private final OfferingQueries offerings;
    private final AvailabilityQueries availability;
    private final CreateBookingUseCase creates;
    private final CancelBookingUseCase cancels;
    private final BookingRepository bookings;
    private final JoinWaitlistUseCase joins;
    private final LeaveWaitlistUseCase leaves;
    private final WaitlistQueries waitlist;
    private final AdmissionControl admissions;
    private final GateLimits limits;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public PublicController(
            OfferingQueries offerings,
            AvailabilityQueries availability,
            CreateBookingUseCase creates,
            CancelBookingUseCase cancels,
            BookingRepository bookings,
            JoinWaitlistUseCase joins,
            LeaveWaitlistUseCase leaves,
            WaitlistQueries waitlist,
            AdmissionControl admissions,
            GateLimits limits,
            UnitOfWork unitOfWork,
            Clock clock) {
        this.offerings = offerings;
        this.availability = availability;
        this.creates = creates;
        this.cancels = cancels;
        this.bookings = bookings;
        this.joins = joins;
        this.leaves = leaves;
        this.waitlist = waitlist;
        this.admissions = admissions;
        this.limits = limits;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    @GetMapping("/offerings/{offeringId}")
    public PublicOffering getOffering(HttpServletRequest request, @PathVariable UUID offeringId) {
        CustomerClaims claims = require(request, GateScope.browse);
        requireOffering(claims, offeringId);
        OfferingView offering = offerings.get(offeringId);
        return new PublicOffering(
                offering.id(),
                offering.name(),
                offering.kind().name(),
                offering.durationMin(),
                offering.durationMax(),
                offering.durationStep(),
                null);
    }

    @GetMapping("/offerings/{offeringId}/availability")
    public PublicAvailability getAvailability(
            HttpServletRequest request,
            @PathVariable UUID offeringId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) Integer durationMinutes,
            @RequestParam(required = false) UUID resource,
            @RequestParam(required = false) Integer partySize) {
        CustomerClaims claims = require(request, GateScope.browse);
        requireOffering(claims, offeringId);
        return new PublicAvailability(availability
                .availability(offeringId, from, to, durationMinutes, resource, partySize)
                .stream()
                .map(PublicController::slot)
                .toList());
    }

    @PostMapping("/bookings")
    public ResponseEntity<PublicBooking> hold(
            HttpServletRequest request, @RequestBody PublicBookingCreate body) {
        CustomerClaims claims = require(request, GateScope.hold);
        requireOffering(claims, body.offeringId());
        String target = body.sessionId() != null ? "session:" + body.sessionId() : "offering:" + body.offeringId();
        if (!admissions.tryAcquire(target)) {
            throw new GateException(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "ADMISSION_BUSY",
                    "Too many holds are in flight for this target.",
                    ThreadLocalRandom.current().nextInt(1, 4));
        }
        try {
            CreateBookingUseCase.Result result = creates.execute(new CreateBookingUseCase.Command(
                    body.offeringId(),
                    claims.customerRef(),
                    body.sessionId(),
                    body.poolCode(),
                    body.qty(),
                    body.start(),
                    body.durationMinutes(),
                    body.resourceId(),
                    body.partySize(),
                    null,
                    BookingSource.GATE,
                    null,
                    null));
            return ResponseEntity.status(HttpStatus.CREATED).body(PublicBooking.of(result.booking()));
        } finally {
            admissions.release(target);
        }
    }

    @DeleteMapping("/bookings/{bookingId}")
    public ResponseEntity<Void> release(HttpServletRequest request, @PathVariable UUID bookingId) {
        CustomerClaims claims = require(request, GateScope.hold);
        Booking booking = unitOfWork.transactional(() -> bookings
                .lockById(bookingId)
                .filter(found -> found.source() == BookingSource.GATE)
                .filter(found -> found.customerRef().equals(claims.customerRef()))
                .filter(found -> found.status() == BookingStatus.HELD)
                .orElseThrow(() -> new NotFoundException("No booking exists here with that id.")));
        cancels.execute(booking.id().value());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/waitlist")
    public ResponseEntity<PublicWaitlistEntry> joinWaitlist(
            HttpServletRequest request, @RequestBody PublicWaitlistJoin body) {
        CustomerClaims claims = require(request, GateScope.waitlist);
        requireOffering(claims, body.offeringId());
        JoinWaitlistUseCase.Result result = joins.execute(new JoinWaitlistUseCase.Command(
                body.offeringId(),
                claims.customerRef(),
                body.qty(),
                body.sessionId(),
                body.poolCode(),
                body.startsAt(),
                body.endsAt(),
                body.resourceId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PublicWaitlistEntry.of(new WaitlistView(
                        result.entry().id(),
                        result.entry().offeringId(),
                        result.entry().customerRef(),
                        result.entry().qty(),
                        result.entry().status().name(),
                        result.position(),
                        result.entry().sessionId(),
                        result.entry().poolCode(),
                        result.entry().desiredStartsAt(),
                        result.entry().desiredEndsAt(),
                        result.entry().offeredBookingId(),
                        result.entry().offeredUntil(),
                        result.entry().createdAt())));
    }

    @GetMapping("/waitlist/{entryId}")
    public PublicWaitlistEntry getWaitlistEntry(
            HttpServletRequest request, @PathVariable UUID entryId) {
        CustomerClaims claims = require(request, GateScope.waitlist);
        return PublicWaitlistEntry.of(owned(claims, entryId));
    }

    @DeleteMapping("/waitlist/{entryId}")
    public ResponseEntity<Void> leaveWaitlist(
            HttpServletRequest request, @PathVariable UUID entryId) {
        CustomerClaims claims = require(request, GateScope.waitlist);
        owned(claims, entryId);
        leaves.execute(entryId);
        return ResponseEntity.noContent().build();
    }

    private WaitlistView owned(CustomerClaims claims, UUID entryId) {
        WaitlistView view = waitlist.get(entryId);
        if (!view.customerRef().equals(claims.customerRef())) {
            throw new NotFoundException("No waitlist entry exists here with that id.");
        }
        return view;
    }

    private static void requireOffering(CustomerClaims claims, UUID offeringId) {
        if (!claims.allowsOffering(offeringId)) {
            throw new GateException(HttpStatus.FORBIDDEN.value(), "CUSTOMER_FORBIDDEN", "The token is bound to another offering.");
        }
    }

    private static CustomerClaims require(HttpServletRequest request, GateScope scope) {
        CustomerClaims claims = GateAttributes.require(request);
        if (!claims.has(scope)) {
            throw new GateException(HttpStatus.FORBIDDEN.value(), "CUSTOMER_FORBIDDEN", "The token lacks the " + scope + " scope.");
        }
        return claims;
    }

    private static PublicAvailabilitySlot slot(AvailabilitySlot slot) {
        return new PublicAvailabilitySlot(slot.start(), slot.end(), slot.resourceIds());
    }
}
