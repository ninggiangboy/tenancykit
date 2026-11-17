package dev.ngb.booking.booking.domain.series;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A repeating booking: the recurrence the Tenant sent and the mode it chose, while every occurrence is an ordinary
 * booking that names this series.
 *
 * <p>The series stores the lines rather than the expanded occurrences, so cancelling it cancels the bookings that exist
 * rather than editing a rule, and a later reader sees exactly what the Tenant asked for. Its version rises on a change,
 * like a booking's, so a receiver compares it rather than re-reading.
 */
public final class BookingSeries {

    private final UUID id;
    private final TenantId tenantId;
    private final UUID offeringId;
    private final String customerRef;
    private final String zone;
    private final String dtstart;
    private final List<String> rule;
    private final SeriesMode mode;
    private final Integer durationMinutes;
    private final String poolCode;
    private final Instant createdAt;
    private SeriesStatus status;
    private long version;
    private Instant updatedAt;

    private BookingSeries(
            UUID id,
            TenantId tenantId,
            UUID offeringId,
            String customerRef,
            String zone,
            String dtstart,
            List<String> rule,
            SeriesMode mode,
            SeriesStatus status,
            Integer durationMinutes,
            String poolCode,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.offeringId = Objects.requireNonNull(offeringId, "offeringId");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.dtstart = Objects.requireNonNull(dtstart, "dtstart");
        this.rule = List.copyOf(rule);
        this.mode = Objects.requireNonNull(mode, "mode");
        this.status = Objects.requireNonNull(status, "status");
        this.durationMinutes = durationMinutes;
        this.poolCode = poolCode;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static BookingSeries create(
            UUID id,
            TenantId tenantId,
            UUID offeringId,
            String customerRef,
            String zone,
            String dtstart,
            List<String> rule,
            SeriesMode mode,
            Integer durationMinutes,
            String poolCode,
            Instant now) {
        return new BookingSeries(
                id,
                tenantId,
                offeringId,
                customerRef,
                zone,
                dtstart,
                rule,
                mode,
                SeriesStatus.ACTIVE,
                durationMinutes,
                poolCode,
                1,
                now,
                now);
    }

    public static BookingSeries restore(
            UUID id,
            TenantId tenantId,
            UUID offeringId,
            String customerRef,
            String zone,
            String dtstart,
            List<String> rule,
            SeriesMode mode,
            SeriesStatus status,
            Integer durationMinutes,
            String poolCode,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new BookingSeries(
                id,
                tenantId,
                offeringId,
                customerRef,
                zone,
                dtstart,
                rule,
                mode,
                status,
                durationMinutes,
                poolCode,
                version,
                createdAt,
                updatedAt);
    }

    /** @return false when the series is already cancelled, which makes a repeated cancel harmless */
    public boolean cancel(Instant now) {
        if (status == SeriesStatus.CANCELLED) {
            return false;
        }
        status = SeriesStatus.CANCELLED;
        version++;
        updatedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID offeringId() {
        return offeringId;
    }

    public String customerRef() {
        return customerRef;
    }

    public String zone() {
        return zone;
    }

    public String dtstart() {
        return dtstart;
    }

    public List<String> rule() {
        return rule;
    }

    public SeriesMode mode() {
        return mode;
    }

    public SeriesStatus status() {
        return status;
    }

    public Integer durationMinutes() {
        return durationMinutes;
    }

    public String poolCode() {
        return poolCode;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
