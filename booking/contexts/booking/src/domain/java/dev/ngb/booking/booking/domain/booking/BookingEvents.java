package dev.ngb.booking.booking.domain.booking;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the booking context announces: a hold was taken, confirmed, cancelled or left to lapse.
 *
 * <p>Every event carries the booking's complete current state, so a receiver that keeps bookings can rebuild its own
 * view without calling back, and the version it carries is monotonic per booking, so a delivery that arrives late is
 * dropped rather than applied over newer state. The payload names the same fields the committed schema under
 * {@code contracts/events/booking} requires and nothing else: an added field would be refused by a consumer that
 * validates against it.
 *
 * <p>Nothing here is personal data: {@code customerRef} is the Tenant's own opaque reference, which is why an event on
 * a shared broker may carry it.
 */
public final class BookingEvents {

    public static final String HELD = "booking.held";
    public static final String CONFIRMED = "booking.confirmed";
    public static final String CANCELLED = "booking.cancelled";
    public static final String EXPIRED = "booking.expired";

    /** How a booking that was never cancelled reports itself: no cancellation window matched a plain give-back. */
    public static final String NO_POLICY = "NONE";

    private BookingEvents() {}

    public static Held held(UUID eventId, Booking booking, Instant occurredAt) {
        return new Held(
                eventId,
                booking.tenantId(),
                booking.id().value(),
                booking.version(),
                booking.customerRef(),
                booking.offeringId(),
                booking.offeringKind(),
                booking.source(),
                booking.qty(),
                booking.sessionId(),
                booking.poolCode(),
                booking.startsAt(),
                booking.endsAt(),
                booking.resources(),
                booking.seriesId(),
                booking.holdExpiresAt(),
                occurredAt);
    }

    public static Confirmed confirmed(UUID eventId, Booking booking, Instant occurredAt) {
        return new Confirmed(
                eventId,
                booking.tenantId(),
                booking.id().value(),
                booking.version(),
                booking.customerRef(),
                booking.offeringId(),
                booking.offeringKind(),
                booking.source(),
                booking.qty(),
                booking.sessionId(),
                booking.poolCode(),
                booking.startsAt(),
                booking.endsAt(),
                booking.resources(),
                booking.seriesId(),
                booking.confirmedAt(),
                occurredAt);
    }

    public static Cancelled cancelled(UUID eventId, Booking booking, Instant occurredAt) {
        return new Cancelled(
                eventId,
                booking.tenantId(),
                booking.id().value(),
                booking.version(),
                booking.customerRef(),
                booking.offeringId(),
                booking.offeringKind(),
                booking.source(),
                booking.qty(),
                booking.sessionId(),
                booking.poolCode(),
                booking.startsAt(),
                booking.endsAt(),
                booking.resources(),
                booking.seriesId(),
                booking.cancelledAt(),
                booking.policyCode(),
                occurredAt);
    }

    public static Expired expired(UUID eventId, Booking booking, Instant occurredAt) {
        return new Expired(
                eventId,
                booking.tenantId(),
                booking.id().value(),
                booking.version(),
                booking.customerRef(),
                booking.offeringId(),
                booking.offeringKind(),
                booking.source(),
                booking.qty(),
                booking.sessionId(),
                booking.poolCode(),
                booking.startsAt(),
                booking.endsAt(),
                booking.resources(),
                booking.seriesId(),
                booking.holdExpiresAt(),
                occurredAt);
    }

    /** A hold was taken and the Tenant may now charge through Billing and confirm it. */
    public record Held(
            UUID eventId,
            TenantId tenantId,
            UUID bookingId,
            long bookingVersion,
            String customerRef,
            UUID offeringId,
            String offeringKind,
            BookingSource source,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResource> resources,
            UUID seriesId,
            Instant holdExpiresAt,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return HELD;
        }

        @Override
        public String aggregate() {
            return "booking/" + bookingId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = base(
                    bookingId,
                    bookingVersion,
                    BookingStatus.HELD,
                    customerRef,
                    offeringId,
                    offeringKind,
                    source,
                    qty,
                    sessionId,
                    poolCode,
                    startsAt,
                    endsAt,
                    resources,
                    seriesId);
            payload.put("holdExpiresAt", text(holdExpiresAt));
            payload.put("occurredAt", text(occurredAt));
            return payload;
        }
    }

    /** The claims are permanent until the booking is cancelled or marked a no-show. */
    public record Confirmed(
            UUID eventId,
            TenantId tenantId,
            UUID bookingId,
            long bookingVersion,
            String customerRef,
            UUID offeringId,
            String offeringKind,
            BookingSource source,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResource> resources,
            UUID seriesId,
            Instant confirmedAt,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return CONFIRMED;
        }

        @Override
        public String aggregate() {
            return "booking/" + bookingId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = base(
                    bookingId,
                    bookingVersion,
                    BookingStatus.CONFIRMED,
                    customerRef,
                    offeringId,
                    offeringKind,
                    source,
                    qty,
                    sessionId,
                    poolCode,
                    startsAt,
                    endsAt,
                    resources,
                    seriesId);
            payload.put("confirmedAt", text(confirmedAt));
            payload.put("occurredAt", text(occurredAt));
            return payload;
        }
    }

    /** The booking was given back and its claims released. */
    public record Cancelled(
            UUID eventId,
            TenantId tenantId,
            UUID bookingId,
            long bookingVersion,
            String customerRef,
            UUID offeringId,
            String offeringKind,
            BookingSource source,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResource> resources,
            UUID seriesId,
            Instant cancelledAt,
            String policyCode,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return CANCELLED;
        }

        @Override
        public String aggregate() {
            return "booking/" + bookingId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = base(
                    bookingId,
                    bookingVersion,
                    BookingStatus.CANCELLED,
                    customerRef,
                    offeringId,
                    offeringKind,
                    source,
                    qty,
                    sessionId,
                    poolCode,
                    startsAt,
                    endsAt,
                    resources,
                    seriesId);
            payload.put("cancelledAt", text(cancelledAt));
            payload.put("policyCode", policyCode);
            payload.put("occurredAt", text(occurredAt));
            return payload;
        }
    }

    /** Nobody confirmed the hold before it lapsed, so the worker released it. */
    public record Expired(
            UUID eventId,
            TenantId tenantId,
            UUID bookingId,
            long bookingVersion,
            String customerRef,
            UUID offeringId,
            String offeringKind,
            BookingSource source,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResource> resources,
            UUID seriesId,
            Instant holdExpiresAt,
            Instant expiredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return EXPIRED;
        }

        @Override
        public String aggregate() {
            return "booking/" + bookingId;
        }

        @Override
        public Instant occurredAt() {
            return expiredAt;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = base(
                    bookingId,
                    bookingVersion,
                    BookingStatus.EXPIRED,
                    customerRef,
                    offeringId,
                    offeringKind,
                    source,
                    qty,
                    sessionId,
                    poolCode,
                    startsAt,
                    endsAt,
                    resources,
                    seriesId);
            payload.put("holdExpiresAt", text(holdExpiresAt));
            payload.put("expiredAt", text(expiredAt));
            payload.put("occurredAt", text(expiredAt));
            return payload;
        }
    }

    /** The fields every state carries: the booking itself, whatever it has become. */
    private static Map<String, Object> base(
            UUID bookingId,
            long bookingVersion,
            BookingStatus status,
            String customerRef,
            UUID offeringId,
            String offeringKind,
            BookingSource source,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResource> resources,
            UUID seriesId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("bookingVersion", bookingVersion);
        payload.put("status", status.name());
        payload.put("customerRef", customerRef);
        payload.put("offeringId", offeringId.toString());
        payload.put("offeringKind", offeringKind);
        payload.put("source", source.name());
        payload.put("qty", qty);
        payload.put("sessionId", text(sessionId));
        payload.put("poolCode", poolCode);
        payload.put("startsAt", text(startsAt));
        payload.put("endsAt", text(endsAt));
        List<Map<String, Object>> assigned = new ArrayList<>(resources.size());
        for (AssignedResource resource : resources) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("slotCode", resource.slotCode());
            one.put("resourceId", resource.resourceId().toString());
            assigned.add(one);
        }
        payload.put("resources", assigned);
        payload.put("seriesId", text(seriesId));
        return payload;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
