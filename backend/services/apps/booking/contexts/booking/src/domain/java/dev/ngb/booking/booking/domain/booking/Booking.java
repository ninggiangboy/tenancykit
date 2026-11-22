package dev.ngb.booking.booking.domain.booking;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A place or a range held for a customer, and the one aggregate the booking context owns.
 *
 * <p>A booking begins {@code HELD}, which is a promise with an end: {@code holdExpiresAt} is when the claims behind it
 * lapse, and a grace beyond it during which a confirmation is still accepted, because a Tenant that charged a minute
 * late should not lose the place to its own clock. Confirming makes the claims permanent, cancelling releases them,
 * and letting the hold lapse expires it; from {@code HELD} exactly one of those happens, which is what the transition
 * methods' {@code boolean} answers: a transition that did not apply changed nothing.
 *
 * <p>Every transition bumps {@code version} and {@code updatedAt} together, and the version is what a receiver of an
 * event compares, so a delivery that arrives late is dropped rather than applied over newer state.
 */
public final class Booking {

    private final BookingId id;
    private final TenantId tenantId;
    private final UUID offeringId;
    private final String offeringKind;
    private final String customerRef;
    private final int qty;
    private UUID sessionId;
    private String poolCode;
    private Instant startsAt;
    private Instant endsAt;
    private final BookingSource source;
    private UUID seriesId;
    private List<AssignedResource> resources;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final Instant createdAt;
    private Instant holdExpiresAt;
    private BookingStatus status;
    private int extendedSeconds;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private Instant noShowAt;
    private String policyCode;
    private long version;
    private Instant updatedAt;

    private Booking(
            BookingId id,
            TenantId tenantId,
            UUID offeringId,
            String offeringKind,
            String customerRef,
            BookingStatus status,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            BookingSource source,
            Instant holdExpiresAt,
            int extendedSeconds,
            Instant confirmedAt,
            Instant cancelledAt,
            String policyCode,
            Instant noShowAt,
            UUID seriesId,
            List<AssignedResource> resources,
            String idempotencyKey,
            String requestFingerprint,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.offeringId = Objects.requireNonNull(offeringId, "offeringId");
        this.offeringKind = Objects.requireNonNull(offeringKind, "offeringKind");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.status = Objects.requireNonNull(status, "status");
        if (qty < 1) {
            throw new IllegalArgumentException("a booking holds at least one place");
        }
        this.qty = qty;
        this.sessionId = sessionId;
        this.poolCode = poolCode;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.source = Objects.requireNonNull(source, "source");
        this.holdExpiresAt = holdExpiresAt;
        this.extendedSeconds = extendedSeconds;
        this.confirmedAt = confirmedAt;
        this.cancelledAt = cancelledAt;
        this.noShowAt = noShowAt;
        this.policyCode = policyCode;
        this.seriesId = seriesId;
        this.resources = List.copyOf(resources);
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** A new hold, which has claimed nothing yet: the caller claims it through the offering's engine next. */
    public static Booking create(
            BookingId id,
            TenantId tenantId,
            UUID offeringId,
            String offeringKind,
            String customerRef,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            BookingSource source,
            Instant holdExpiresAt,
            UUID seriesId,
            List<AssignedResource> resources,
            String idempotencyKey,
            String requestFingerprint,
            Instant now) {
        return new Booking(
                id,
                tenantId,
                offeringId,
                offeringKind,
                customerRef,
                BookingStatus.HELD,
                qty,
                sessionId,
                poolCode,
                startsAt,
                endsAt,
                source,
                holdExpiresAt,
                0,
                null,
                null,
                null,
                null,
                seriesId,
                resources,
                idempotencyKey,
                requestFingerprint,
                1,
                now,
                now);
    }

    /** The row as it is stored, with the claims it names and the resources it took. */
    public static Booking restore(
            BookingId id,
            TenantId tenantId,
            UUID offeringId,
            String offeringKind,
            String customerRef,
            BookingStatus status,
            int qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            BookingSource source,
            Instant holdExpiresAt,
            int extendedSeconds,
            Instant confirmedAt,
            Instant cancelledAt,
            String policyCode,
            Instant noShowAt,
            UUID seriesId,
            List<AssignedResource> resources,
            String idempotencyKey,
            String requestFingerprint,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new Booking(
                id,
                tenantId,
                offeringId,
                offeringKind,
                customerRef,
                status,
                qty,
                sessionId,
                poolCode,
                startsAt,
                endsAt,
                source,
                holdExpiresAt,
                extendedSeconds,
                confirmedAt,
                cancelledAt,
                policyCode,
                noShowAt,
                seriesId,
                resources,
                idempotencyKey,
                requestFingerprint,
                version,
                createdAt,
                updatedAt);
    }

    /**
     * Makes the claims permanent.
     *
     * @return false when the booking is not held, which includes confirming one that already is
     */
    public boolean confirm(Instant now) {
        if (status != BookingStatus.HELD) {
            return false;
        }
        status = BookingStatus.CONFIRMED;
        confirmedAt = now;
        bump(now);
        return true;
    }

    /**
     * Moves the end of the hold.
     *
     * @param newExpiry the expiry the engine is told to hold the claims until
     * @param seconds how much later than the previous expiry this is, which the extension cap counts
     * @return false when the booking is not held
     */
    public boolean extend(Instant newExpiry, int seconds, Instant now) {
        if (status != BookingStatus.HELD) {
            return false;
        }
        if (seconds < 1) {
            throw new IllegalArgumentException("an extension moves the hold by at least one second");
        }
        holdExpiresAt = newExpiry;
        extendedSeconds += seconds;
        bump(now);
        return true;
    }

    /**
     * Gives the place back.
     *
     * @param policyCode which of the offering's cancellation windows the cancellation fell in, {@code NONE} when the
     *     booking was only held
     * @return false when the booking is neither held nor confirmed
     */
    public boolean cancel(Instant now, String policyCode) {
        if (status != BookingStatus.HELD && status != BookingStatus.CONFIRMED) {
            return false;
        }
        status = BookingStatus.CANCELLED;
        cancelledAt = now;
        this.policyCode = policyCode;
        bump(now);
        return true;
    }

    /** @return false when the booking is no longer held, which is what makes the sweep safe to run twice */
    public boolean expire(Instant now) {
        if (status != BookingStatus.HELD) {
            return false;
        }
        status = BookingStatus.EXPIRED;
        bump(now);
        return true;
    }

    /**
     * Moves the booking to a new place, keeping its id, status and customer.
     *
     * <p>The caller releases the old claims and claims the new place in the same transaction, so either the booking is
     * at the new place or it is unchanged. The resources are the ones the new place took, in requirement order.
     *
     * @return false when the booking is neither held nor confirmed
     */
    public boolean reschedule(
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            List<AssignedResource> resources,
            Instant now) {
        if (status != BookingStatus.HELD && status != BookingStatus.CONFIRMED) {
            return false;
        }
        this.sessionId = sessionId;
        this.poolCode = poolCode;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.resources = List.copyOf(resources);
        bump(now);
        return true;
    }

    /**
     * Marks a confirmed booking whose start has passed as a no-show; the claims stay as history.
     *
     * @return false when the booking is not confirmed or its start has not passed
     */
    public boolean markNoShow(Instant now) {
        if (status != BookingStatus.CONFIRMED || startsAt == null || now.isBefore(startsAt)) {
            return false;
        }
        status = BookingStatus.NO_SHOW;
        noShowAt = now;
        bump(now);
        return true;
    }

    /** Whether the hold ended, grace included, strictly before {@code now}: past this a confirmation is refused. */
    public boolean lapsedAt(Instant now, long graceSeconds) {
        return holdExpiresAt != null && now.isAfter(holdExpiresAt.plusSeconds(graceSeconds));
    }

    private void bump(Instant now) {
        version++;
        updatedAt = now;
    }

    public BookingId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID offeringId() {
        return offeringId;
    }

    public String offeringKind() {
        return offeringKind;
    }

    public String customerRef() {
        return customerRef;
    }

    public BookingStatus status() {
        return status;
    }

    public int qty() {
        return qty;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public String poolCode() {
        return poolCode;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public BookingSource source() {
        return source;
    }

    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    public int extendedSeconds() {
        return extendedSeconds;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public Instant noShowAt() {
        return noShowAt;
    }

    public String policyCode() {
        return policyCode;
    }

    public UUID seriesId() {
        return seriesId;
    }

    /** Every resource this booking claimed, in the offering's requirement order. */
    public List<AssignedResource> resources() {
        return resources;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** The digest of the request the key was first used with, or null when the request carried no key. */
    public String requestFingerprint() {
        return requestFingerprint;
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
