package dev.ngb.booking.booking.domain.waitlist;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One customer's place in line for one target: a capacity session's pool or an interval offering's desired range.
 *
 * <p>An offer is an ordinary hold: this entry points at the booking the worker created and follows its fate. The entry
 * keeps its position while waiting, so an offer that does not fit is not a reason to lose the line; only leaving, or an
 * offer that ended without acceptance, closes it.
 */
public final class WaitlistEntry {

    private final UUID id;
    private final TenantId tenantId;
    private final UUID offeringId;
    private final String customerRef;
    private final UUID sessionId;
    private final String poolCode;
    private final Instant desiredStartsAt;
    private final Instant desiredEndsAt;
    private final int qty;
    private final UUID resourceId;
    private final Instant createdAt;
    private WaitlistStatus status;
    private UUID offeredBookingId;
    private Instant offeredUntil;
    private Instant updatedAt;

    private WaitlistEntry(
            UUID id,
            TenantId tenantId,
            UUID offeringId,
            String customerRef,
            UUID sessionId,
            String poolCode,
            Instant desiredStartsAt,
            Instant desiredEndsAt,
            int qty,
            UUID resourceId,
            WaitlistStatus status,
            UUID offeredBookingId,
            Instant offeredUntil,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.offeringId = Objects.requireNonNull(offeringId, "offeringId");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.sessionId = sessionId;
        this.poolCode = poolCode;
        this.desiredStartsAt = desiredStartsAt;
        this.desiredEndsAt = desiredEndsAt;
        if (qty < 1) {
            throw new IllegalArgumentException("a waiting entry asks for at least one place");
        }
        this.qty = qty;
        this.resourceId = resourceId;
        this.status = Objects.requireNonNull(status, "status");
        this.offeredBookingId = offeredBookingId;
        this.offeredUntil = offeredUntil;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static WaitlistEntry join(
            UUID id,
            TenantId tenantId,
            UUID offeringId,
            String customerRef,
            UUID sessionId,
            String poolCode,
            Instant desiredStartsAt,
            Instant desiredEndsAt,
            int qty,
            UUID resourceId,
            Instant now) {
        return new WaitlistEntry(
                id,
                tenantId,
                offeringId,
                customerRef,
                sessionId,
                poolCode,
                desiredStartsAt,
                desiredEndsAt,
                qty,
                resourceId,
                WaitlistStatus.WAITING,
                null,
                null,
                now,
                now);
    }

    public static WaitlistEntry restore(
            UUID id,
            TenantId tenantId,
            UUID offeringId,
            String customerRef,
            UUID sessionId,
            String poolCode,
            Instant desiredStartsAt,
            Instant desiredEndsAt,
            int qty,
            UUID resourceId,
            WaitlistStatus status,
            UUID offeredBookingId,
            Instant offeredUntil,
            Instant createdAt,
            Instant updatedAt) {
        return new WaitlistEntry(
                id,
                tenantId,
                offeringId,
                customerRef,
                sessionId,
                poolCode,
                desiredStartsAt,
                desiredEndsAt,
                qty,
                resourceId,
                status,
                offeredBookingId,
                offeredUntil,
                createdAt,
                updatedAt);
    }

    /** @return false when the entry is no longer waiting, which is what makes a repeated offer harmless */
    public boolean offer(UUID bookingId, Instant until, Instant now) {
        if (status != WaitlistStatus.WAITING) {
            return false;
        }
        status = WaitlistStatus.OFFERED;
        offeredBookingId = bookingId;
        offeredUntil = until;
        updatedAt = now;
        return true;
    }

    /** The offered hold was confirmed: the entry stops waiting and keeps the booking as its outcome. */
    public boolean accept(Instant now) {
        if (status != WaitlistStatus.OFFERED) {
            return false;
        }
        status = WaitlistStatus.ACCEPTED;
        updatedAt = now;
        return true;
    }

    /** The offer ended without an acceptance; the entry leaves the line and the next one is tried. */
    public boolean lapse(Instant now) {
        if (status != WaitlistStatus.OFFERED) {
            return false;
        }
        status = WaitlistStatus.LAPSED;
        updatedAt = now;
        return true;
    }

    /** The customer left the line. An offered entry cannot leave: its hold must be released instead. */
    public boolean cancel(Instant now) {
        if (status != WaitlistStatus.WAITING) {
            return false;
        }
        status = WaitlistStatus.CANCELLED;
        updatedAt = now;
        return true;
    }

    public String scope() {
        return sessionId != null ? "session:" + sessionId : "offering:" + offeringId;
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

    public UUID sessionId() {
        return sessionId;
    }

    public String poolCode() {
        return poolCode;
    }

    public Instant desiredStartsAt() {
        return desiredStartsAt;
    }

    public Instant desiredEndsAt() {
        return desiredEndsAt;
    }

    public int qty() {
        return qty;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public WaitlistStatus status() {
        return status;
    }

    public UUID offeredBookingId() {
        return offeredBookingId;
    }

    public Instant offeredUntil() {
        return offeredUntil;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
