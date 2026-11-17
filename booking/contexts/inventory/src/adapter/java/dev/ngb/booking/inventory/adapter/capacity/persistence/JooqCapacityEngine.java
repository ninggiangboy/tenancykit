package dev.ngb.booking.inventory.adapter.capacity.persistence;

import static dev.ngb.booking_jooq.Tables.CAPACITY_UNITS;

import dev.ngb.booking.inventory.api.engine.CapacityClaim;
import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.ClaimRequest;
import dev.ngb.booking.inventory.api.engine.Claimed;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import dev.ngb.booking.inventory.application.capacity.service.CapacityRules;
import dev.ngb.booking.inventory.domain.capacity.UnitStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The flash-sale engine: a pool's places are rows, and a claim takes rows rather than incrementing a counter.
 *
 * <p>The rows are chosen with {@code order by id limit qty for update skip locked}, so two callers take different
 * places instead of queueing on one row and neither can take a place the other is holding; the conditional update
 * that follows is what makes a claim all or nothing. A hold a booking never confirmed is freed by the claim path
 * itself, once, before the rows are chosen, so a lapsed hold does not need a worker to become available again.
 */
public class JooqCapacityEngine implements InventoryEngine {

    private static final String AVAILABLE = UnitStatus.AVAILABLE.name();
    private static final String HELD = UnitStatus.HELD.name();
    private static final String BOOKED = UnitStatus.BOOKED.name();

    private final DSLContext dsl;
    private final CapacityUnitReader units;
    private final CapacityRules rules;

    public JooqCapacityEngine(DSLContext dsl, CapacityUnitReader units, CapacityRules rules) {
        this.dsl = dsl;
        this.units = units;
        this.rules = rules;
    }

    @Override
    public Capability capability() {
        return Capability.CAPACITY_FLASH_SALE;
    }

    @Override
    public ClaimOutcome claim(ClaimRequest request) {
        if (!(request instanceof CapacityClaim claim)) {
            throw new IllegalArgumentException("the capacity engine claims places of a pool");
        }
        rules.requireQty(claim.qty());
        reclaimLapsedHolds(claim);
        List<UUID> picked = pick(claim);
        if (picked.size() < claim.qty()) {
            return new Conflict(
                    Conflict.SOLD_OUT,
                    "The pool has " + picked.size() + " of the " + claim.qty() + " places the claim asks for.",
                    units.remaining(claim.sessionId(), claim.poolCode()));
        }
        int held = hold(claim, picked);
        if (held != picked.size()) {
            throw new IllegalStateException("only " + held + " of " + picked.size() + " chosen places were taken");
        }
        return new Claimed(claim.bookingId(), picked.get(0), claim.expiresAt());
    }

    @Override
    public void confirm(UUID bookingId) {
        int booked = dsl.update(CAPACITY_UNITS)
                .set(CAPACITY_UNITS.STATUS, BOOKED)
                .setNull(CAPACITY_UNITS.HELD_UNTIL)
                .where(CAPACITY_UNITS.BOOKING_ID.eq(bookingId))
                .and(CAPACITY_UNITS.STATUS.eq(HELD))
                .execute();
        if (booked == 0) {
            throw new IllegalStateException("the booking " + bookingId + " holds no place to confirm");
        }
    }

    @Override
    public void release(UUID bookingId) {
        dsl.update(CAPACITY_UNITS)
                .set(CAPACITY_UNITS.STATUS, AVAILABLE)
                .setNull(CAPACITY_UNITS.BOOKING_ID)
                .setNull(CAPACITY_UNITS.HELD_UNTIL)
                .where(CAPACITY_UNITS.BOOKING_ID.eq(bookingId))
                .and(CAPACITY_UNITS.STATUS.eq(HELD))
                .execute();
    }

    @Override
    public void extend(UUID bookingId, Instant until) {
        dsl.update(CAPACITY_UNITS)
                .set(CAPACITY_UNITS.HELD_UNTIL, until)
                .where(CAPACITY_UNITS.BOOKING_ID.eq(bookingId))
                .and(CAPACITY_UNITS.STATUS.eq(HELD))
                .execute();
    }

    /** Frees this pool's holds whose expiry plus the claim's grace has passed, which is the lazy reclaim path. */
    private void reclaimLapsedHolds(CapacityClaim claim) {
        Instant lapsedBefore = claim.now().minusSeconds(claim.graceSeconds());
        dsl.update(CAPACITY_UNITS)
                .set(CAPACITY_UNITS.STATUS, AVAILABLE)
                .setNull(CAPACITY_UNITS.BOOKING_ID)
                .setNull(CAPACITY_UNITS.HELD_UNTIL)
                .where(CAPACITY_UNITS.SESSION_ID.eq(claim.sessionId()))
                .and(CAPACITY_UNITS.POOL_CODE.eq(claim.poolCode()))
                .and(CAPACITY_UNITS.STATUS.eq(HELD))
                .and(CAPACITY_UNITS.HELD_UNTIL.lt(lapsedBefore))
                .execute();
    }

    /** The free places the claim picks, locked against every other caller until this transaction ends. */
    private List<UUID> pick(CapacityClaim claim) {
        return dsl.select(CAPACITY_UNITS.ID)
                .from(CAPACITY_UNITS)
                .where(CAPACITY_UNITS.SESSION_ID.eq(claim.sessionId()))
                .and(CAPACITY_UNITS.POOL_CODE.eq(claim.poolCode()))
                .and(CAPACITY_UNITS.STATUS.eq(AVAILABLE))
                .orderBy(CAPACITY_UNITS.ID)
                .limit(claim.qty())
                .forUpdate()
                .skipLocked()
                .fetch(CAPACITY_UNITS.ID);
    }

    /** Takes exactly the chosen rows, and reports how many the update actually reached. */
    private int hold(CapacityClaim claim, List<UUID> picked) {
        Instant expiresAt = claim.expiresAt();
        if (expiresAt == null) {
            return dsl.update(CAPACITY_UNITS)
                    .set(CAPACITY_UNITS.STATUS, BOOKED)
                    .set(CAPACITY_UNITS.BOOKING_ID, claim.bookingId())
                    .setNull(CAPACITY_UNITS.HELD_UNTIL)
                    .where(CAPACITY_UNITS.ID.in(picked))
                    .execute();
        }
        return dsl.update(CAPACITY_UNITS)
                .set(CAPACITY_UNITS.STATUS, HELD)
                .set(CAPACITY_UNITS.BOOKING_ID, claim.bookingId())
                .set(CAPACITY_UNITS.HELD_UNTIL, expiresAt)
                .where(CAPACITY_UNITS.ID.in(picked))
                .execute();
    }
}
