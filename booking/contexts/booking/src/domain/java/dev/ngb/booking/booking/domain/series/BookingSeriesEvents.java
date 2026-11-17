package dev.ngb.booking.booking.domain.series;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the booking context announces about a series: it was created with its recurrence, or it was cancelled and its
 * future occurrences released.
 *
 * <p>The created event reports counts rather than the expanded occurrences, because the recurrence is the fact; the
 * cancelled one reports how many occurrences this call released, because the Tenant decides what that means for the
 * occurrences it had already confirmed.
 */
public final class BookingSeriesEvents {

    public static final String CREATED = "series.created";
    public static final String CANCELLED = "series.cancelled";

    private BookingSeriesEvents() {}

    public static Created created(
            UUID eventId,
            TenantId tenantId,
            BookingSeries series,
            int occurrenceCount,
            int bookedCount,
            Instant firstStartsAt,
            Instant lastEndsAt,
            Instant occurredAt) {
        return new Created(
                eventId,
                tenantId,
                series.id(),
                series.version(),
                series.offeringId(),
                series.customerRef(),
                series.mode(),
                occurrenceCount,
                bookedCount,
                firstStartsAt,
                lastEndsAt,
                occurredAt);
    }

    public static Cancelled cancelled(
            UUID eventId, TenantId tenantId, BookingSeries series, int cancelledOccurrences, Instant occurredAt) {
        return new Cancelled(
                eventId,
                tenantId,
                series.id(),
                series.version(),
                series.offeringId(),
                series.customerRef(),
                cancelledOccurrences,
                occurredAt);
    }

    public record Created(
            UUID eventId,
            TenantId tenantId,
            UUID seriesId,
            long seriesVersion,
            UUID offeringId,
            String customerRef,
            SeriesMode mode,
            int occurrenceCount,
            int bookedCount,
            Instant firstStartsAt,
            Instant lastEndsAt,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return CREATED;
        }

        @Override
        public String aggregate() {
            return "series/" + seriesId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seriesId", seriesId.toString());
            payload.put("seriesVersion", seriesVersion);
            payload.put("offeringId", offeringId.toString());
            payload.put("customerRef", customerRef);
            payload.put("mode", mode.name());
            payload.put("status", SeriesStatus.ACTIVE.name());
            payload.put("occurrenceCount", occurrenceCount);
            payload.put("bookedCount", bookedCount);
            payload.put("firstStartsAt", firstStartsAt.toString());
            payload.put("lastEndsAt", lastEndsAt.toString());
            payload.put("occurredAt", occurredAt.toString());
            return payload;
        }
    }

    public record Cancelled(
            UUID eventId,
            TenantId tenantId,
            UUID seriesId,
            long seriesVersion,
            UUID offeringId,
            String customerRef,
            int cancelledOccurrences,
            Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return CANCELLED;
        }

        @Override
        public String aggregate() {
            return "series/" + seriesId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seriesId", seriesId.toString());
            payload.put("seriesVersion", seriesVersion);
            payload.put("offeringId", offeringId.toString());
            payload.put("customerRef", customerRef);
            payload.put("status", SeriesStatus.CANCELLED.name());
            payload.put("cancelledOccurrences", cancelledOccurrences);
            payload.put("occurredAt", occurredAt.toString());
            return payload;
        }
    }
}
