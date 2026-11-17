package dev.ngb.booking.booking.domain.waitlist;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What booking announces about the waitlist: an entry was offered a hold, or its offer ended without an acceptance and
 * the next entry is tried.
 *
 * <p>Both carry the entry rather than a position in a mutable list, so a receiver reconciles the customer it already
 * knows; the offered event's position is informational and says how many entries were ahead when the offer was made.
 */
public final class WaitlistEvents {

    public static final String OFFERED = "waitlist.offered";
    public static final String LAPSED = "waitlist.lapsed";

    private WaitlistEvents() {}

    public static Offered offered(
            UUID eventId,
            TenantId tenantId,
            WaitlistEntry entry,
            String offeringKind,
            int position,
            UUID offeredBookingId,
            Instant offeredUntil,
            Instant occurredAt) {
        return new Offered(
                eventId,
                tenantId,
                entry.id(),
                entry.offeringId(),
                entry.customerRef(),
                offeringKind,
                entry.qty(),
                entry.sessionId(),
                entry.poolCode(),
                entry.desiredStartsAt(),
                entry.desiredEndsAt(),
                position,
                offeredBookingId,
                offeredUntil,
                occurredAt);
    }

    public static Lapsed lapsed(
            UUID eventId,
            TenantId tenantId,
            WaitlistEntry entry,
            UUID offeredBookingId,
            String reason,
            Instant occurredAt) {
        return new Lapsed(
                eventId,
                tenantId,
                entry.id(),
                entry.offeringId(),
                entry.customerRef(),
                offeredBookingId,
                reason,
                occurredAt);
    }

    public record Offered(
            UUID eventId,
            TenantId tenantId,
            UUID waitlistEntryId,
            UUID offeringId,
            String customerRef,
            String offeringKind,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            int position,
            UUID offeredBookingId,
            Instant offeredUntil,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return OFFERED;
        }

        @Override
        public String aggregate() {
            return "waitlist/" + waitlistEntryId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("waitlistEntryId", waitlistEntryId.toString());
            payload.put("offeringId", offeringId.toString());
            payload.put("customerRef", customerRef);
            payload.put("offeringKind", offeringKind);
            payload.put("qty", qty);
            payload.put("sessionId", text(sessionId));
            payload.put("poolCode", poolCode);
            payload.put("startsAt", text(startsAt));
            payload.put("endsAt", text(endsAt));
            payload.put("position", position);
            payload.put("offeredBookingId", offeredBookingId.toString());
            payload.put("offeredUntil", offeredUntil.toString());
            payload.put("occurredAt", occurredAt.toString());
            return payload;
        }
    }

    public record Lapsed(
            UUID eventId,
            TenantId tenantId,
            UUID waitlistEntryId,
            UUID offeringId,
            String customerRef,
            UUID offeredBookingId,
            String reason,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return LAPSED;
        }

        @Override
        public String aggregate() {
            return "waitlist/" + waitlistEntryId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("waitlistEntryId", waitlistEntryId.toString());
            payload.put("offeringId", offeringId.toString());
            payload.put("customerRef", customerRef);
            payload.put("offeredBookingId", text(offeredBookingId));
            payload.put("reason", reason);
            payload.put("occurredAt", occurredAt.toString());
            return payload;
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
