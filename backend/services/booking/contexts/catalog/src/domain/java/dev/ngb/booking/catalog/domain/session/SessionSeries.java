package dev.ngb.booking.catalog.domain.session;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A repeating capacity offering: the zone its wall-clock start is read in, the iCalendar content lines that repeat it,
 * how long one occurrence lasts and how many places it offers.
 *
 * <p>The rule is kept as the lines the Tenant sent, never as resolved instants, so a change of a zone's rules or a
 * different horizon never needs the series to be rewritten. The version is the ETag: a change moves the instant and
 * bumps it, and a body identical to what is stored changes nothing at all.
 */
public final class SessionSeries {

    private final SessionSeriesId id;
    private final TenantId tenantId;
    private final UUID offeringId;
    private final String zone;
    private final String dtstart;
    private List<String> rule;
    private int durationMinutes;
    private int capacity;
    private SeriesStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private SessionSeries(
            SessionSeriesId id,
            TenantId tenantId,
            UUID offeringId,
            String zone,
            String dtstart,
            List<String> rule,
            int durationMinutes,
            int capacity,
            SeriesStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.offeringId = Objects.requireNonNull(offeringId, "offeringId");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.dtstart = Objects.requireNonNull(dtstart, "dtstart");
        this.rule = List.copyOf(Objects.requireNonNull(rule, "rule"));
        if (this.rule.isEmpty()) {
            throw new IllegalArgumentException("a session series repeats a rule and carries none");
        }
        if (durationMinutes < 1) {
            throw new IllegalArgumentException("durationMinutes must be at least 1");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.durationMinutes = durationMinutes;
        this.capacity = capacity;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A series that did not exist until now: active, with nothing materialized yet. */
    public static SessionSeries create(
            SessionSeriesId id,
            TenantId tenantId,
            UUID offeringId,
            String zone,
            String dtstart,
            List<String> rule,
            int durationMinutes,
            int capacity,
            Instant now) {
        return new SessionSeries(
                id, tenantId, offeringId, zone, dtstart, rule, durationMinutes, capacity, SeriesStatus.ACTIVE, now, now,
                1);
    }

    /** The series a repository reads back from storage. */
    public static SessionSeries restore(
            SessionSeriesId id,
            TenantId tenantId,
            UUID offeringId,
            String zone,
            String dtstart,
            List<String> rule,
            int durationMinutes,
            int capacity,
            SeriesStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new SessionSeries(
                id, tenantId, offeringId, zone, dtstart, rule, durationMinutes, capacity, status, createdAt, updatedAt,
                version);
    }

    /**
     * Replaces the changeable properties with the values the caller resolved; a PATCH leaves a property alone by
     * resolving it to what is stored, so an identical body changes nothing and bumps no version.
     *
     * @return whether anything changed, so an identical patch writes nothing
     */
    public boolean change(List<String> newRule, int newDurationMinutes, int newCapacity, SeriesStatus newStatus, Instant now) {
        List<String> stored = List.copyOf(Objects.requireNonNull(newRule, "rule"));
        Objects.requireNonNull(newStatus, "status");
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("a session series repeats a rule and carries none");
        }
        if (newDurationMinutes < 1) {
            throw new IllegalArgumentException("durationMinutes must be at least 1");
        }
        if (newCapacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (rule.equals(stored)
                && durationMinutes == newDurationMinutes
                && capacity == newCapacity
                && status == newStatus) {
            return false;
        }
        this.rule = stored;
        this.durationMinutes = newDurationMinutes;
        this.capacity = newCapacity;
        this.status = newStatus;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /**
     * Appends an exclusion so a cancelled occurrence is not materialized again.
     *
     * @return whether the line was added; a line already present changes nothing
     */
    public boolean exclude(String exdateLine, Instant now) {
        Objects.requireNonNull(exdateLine, "exdateLine");
        if (rule.contains(exdateLine)) {
            return false;
        }
        List<String> lines = new ArrayList<>(rule);
        lines.add(exdateLine);
        this.rule = List.copyOf(lines);
        this.updatedAt = now;
        this.version++;
        return true;
    }

    public boolean isActive() {
        return status == SeriesStatus.ACTIVE;
    }

    public SessionSeriesId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID offeringId() {
        return offeringId;
    }

    /** The IANA zone the local start is read in. */
    public String zone() {
        return zone;
    }

    /** The first local start, wall-clock and without an offset. */
    public String dtstart() {
        return dtstart;
    }

    /** The iCalendar content lines that repeat the series. */
    public List<String> rule() {
        return rule;
    }

    public int durationMinutes() {
        return durationMinutes;
    }

    public int capacity() {
        return capacity;
    }

    public SeriesStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
