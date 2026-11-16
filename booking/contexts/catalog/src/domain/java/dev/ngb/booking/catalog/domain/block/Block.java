package dev.ngb.booking.catalog.domain.block;

import dev.ngb.core.id.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A closed period on a resource: maintenance, a holiday, a staff absence.
 *
 * <p>A one-off block carries the range it closes and no rule. A recurring one carries iCalendar content lines as well;
 * its two instants are the first occurrence and its length is how long every occurrence lasts. Availability subtracts
 * what the lines expand to inside its window. A block never deletes a booking: a caller is told which bookings it
 * overlaps and decides what to do about them.
 */
public final class Block {

    private final BlockId id;
    private final TenantId tenantId;
    private final UUID resourceId;
    private final String reason;
    private final Instant startsAt;
    private final Instant endsAt;
    private final List<String> rule;
    private final int durationMinutes;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Block(
            BlockId id,
            TenantId tenantId,
            UUID resourceId,
            String reason,
            Instant startsAt,
            Instant endsAt,
            List<String> rule,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
        this.rule = List.copyOf(Objects.requireNonNull(rule, "rule"));
        this.durationMinutes = minutesBetween(startsAt, endsAt);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A block that did not exist until now; its version starts at one. */
    public static Block create(
            BlockId id,
            TenantId tenantId,
            UUID resourceId,
            String reason,
            Instant startsAt,
            Instant endsAt,
            List<String> rule,
            Instant now) {
        return new Block(
                id, tenantId, resourceId, reason, startsAt, endsAt, rule == null ? List.of() : rule, now, now, 1);
    }

    /** The block a repository reads back from storage. */
    public static Block restore(
            BlockId id,
            TenantId tenantId,
            UUID resourceId,
            String reason,
            Instant startsAt,
            Instant endsAt,
            List<String> rule,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Block(
                id, tenantId, resourceId, reason, startsAt, endsAt, rule == null ? List.of() : rule, createdAt,
                updatedAt, version);
    }

    public BlockId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public String reason() {
        return reason;
    }

    /** The first instant the block closes. */
    public Instant startsAt() {
        return startsAt;
    }

    /** The first instant the block reopens. */
    public Instant endsAt() {
        return endsAt;
    }

    /** The iCalendar content lines the block repeats, empty for a one-off block. */
    public List<String> rule() {
        return rule;
    }

    /** How long a closed period lasts: the range's length, or one occurrence's length when the block repeats. */
    public int durationMinutes() {
        return durationMinutes;
    }

    /** Whether the block closes one range or repeats a rule. */
    public boolean isRecurring() {
        return !rule.isEmpty();
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

    private static int minutesBetween(Instant startsAt, Instant endsAt) {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        long minutes = Duration.between(startsAt, endsAt).toMinutes();
        if (minutes < 1) {
            throw new IllegalArgumentException("a block ends after it starts");
        }
        return (int) minutes;
    }
}
