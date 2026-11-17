package dev.ngb.booking.inventory.adapter.capacity.persistence;

import static dev.ngb.booking_jooq.Tables.CAPACITY_UNITS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.booking.inventory.api.engine.CapacityClaim;
import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.Claimed;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.application.capacity.exception.CapacityInUseException;
import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import dev.ngb.booking.inventory.domain.capacity.UnitStatus;
import dev.ngb.booking.support.BookingApiTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A claim is all or nothing, and a pool's places follow the capacity a Tenant sets for it: growing adds free places,
 * shrinking gives free ones back, and a shrink below what a booking holds or has booked is refused.
 */
class CapacityClaimResizeTest extends BookingApiTest {

    private static final String POOL = "default";
    private static final Instant NOW = Instant.parse("2026-05-04T09:00:00Z");

    @Autowired
    List<InventoryEngine> engines;

    @Autowired
    CapacityUnitReader units;

    @Autowired
    SessionPlaces places;

    @Test
    void aClaimOfThreeOnATwoPlacePoolChangesNothing() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 2);

        ClaimOutcome outcome = claim(session, sessionId, UUID.randomUUID(), 3);

        assertThat(outcome).isInstanceOf(Conflict.class);
        assertThat(((Conflict) outcome).code()).isEqualTo(Conflict.SOLD_OUT);
        assertThat(((Conflict) outcome).remaining()).isEqualTo(2);
        assertThat(committed(session, sessionId)).as("an all-or-nothing claim leaves the two free places alone").isZero();
        assertThat(remaining(session, sessionId)).isEqualTo(2);
    }

    @Test
    void growingAddsFreePlacesAndShrinkingBelowTheHoldsIsRefused() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 2);
        UUID booking = UUID.randomUUID();
        assertThat(claim(session, sessionId, booking, 2)).isInstanceOf(Claimed.class);
        assertThat(remaining(session, sessionId)).isZero();

        resize(session, sessionId, 5);
        assertThat(total(session, sessionId)).isEqualTo(5);
        assertThat(remaining(session, sessionId)).as("a grown pool offers the places it gained").isEqualTo(3);
        assertThat(committed(session, sessionId)).isEqualTo(2);

        CapacityInUseException refused = catchThrowableOfType(
                CapacityInUseException.class, () -> resize(session, sessionId, 1));
        assertThat(refused.code()).isEqualTo(CapacityInUseException.CODE);
        assertThat(total(session, sessionId)).as("a refused resize changes nothing").isEqualTo(5);
        assertThat(committed(session, sessionId)).isEqualTo(2);

        resize(session, sessionId, 2);
        assertThat(total(session, sessionId)).as("a shrink to the held places removes free ones only").isEqualTo(2);
        assertThat(remaining(session, sessionId)).isZero();
        assertThat(committed(session, sessionId)).isEqualTo(2);
    }

    @Test
    void releasingFreesThePlacesForTheNextClaim() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 1);
        UUID booking = UUID.randomUUID();
        assertThat(claim(session, sessionId, booking, 1)).isInstanceOf(Claimed.class);
        assertThat(claim(session, sessionId, UUID.randomUUID(), 1)).isInstanceOf(Conflict.class);

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> capacity().release(booking)));

        assertThat(remaining(session, sessionId)).isEqualTo(1);
        assertThat(committed(session, sessionId)).isZero();
        assertThat(claim(session, sessionId, UUID.randomUUID(), 1)).isInstanceOf(Claimed.class);
    }

    @Test
    void confirmingCommitsTheHoldAndAShrinkBelowItIsRefused() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 3);
        UUID booking = UUID.randomUUID();
        assertThat(claim(session, sessionId, booking, 2)).isInstanceOf(Claimed.class);

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> capacity().confirm(booking)));

        assertThat(rowsIn(session, sessionId, UnitStatus.BOOKED)).isEqualTo(2);
        assertThat(rowsIn(session, sessionId, UnitStatus.HELD)).isZero();
        assertThat(committed(session, sessionId)).isEqualTo(2);

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> capacity().release(booking)));

        assertThat(committed(session, sessionId)).as("a release never gives a committed place back").isEqualTo(2);

        CapacityInUseException refused = catchThrowableOfType(
                CapacityInUseException.class, () -> resize(session, sessionId, 1));
        assertThat(refused.code()).isEqualTo(CapacityInUseException.CODE);
        assertThat(total(session, sessionId)).isEqualTo(3);
    }

    @Test
    void extendingMovesTheHoldOutOfReachOfAnotherClaim() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 1);
        UUID booking = UUID.randomUUID();
        assertThat(claim(session, sessionId, booking, 1)).isInstanceOf(Claimed.class);

        inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> capacity().extend(booking, NOW.minusSeconds(60))));

        assertThat(claim(session, sessionId, UUID.randomUUID(), 1))
                .as("the place is free again once the moved hold has lapsed")
                .isInstanceOf(Claimed.class);
        assertThat(committed(session, sessionId)).isEqualTo(1);
    }

    private ClaimOutcome claim(Session session, UUID sessionId, UUID bookingId, int qty) {
        CapacityClaim claim = new CapacityClaim(bookingId, sessionId, POOL, qty, NOW.plusSeconds(600), NOW, 30);
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> capacity().claim(claim)));
    }

    private void resize(Session session, UUID sessionId, int capacity) {
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> places.resizePlaces(sessionId, POOL, capacity)));
    }

    private InventoryEngine capacity() {
        return engines.stream()
                .filter(engine -> engine.capability() == InventoryEngine.Capability.CAPACITY_FLASH_SALE)
                .findFirst()
                .orElseThrow();
    }

    private UUID seedSession(Session session, int capacity) {
        UUID offeringId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            dsl.execute(
                    """
                    insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                           horizon_days, created_at, updated_at)
                    values (?, ?, ?, 'Resize capacity', 'CAPACITY', 'ACTIVE', 60, 60, 90, now(), now())
                    """,
                    session.tenant().value(),
                    offeringId,
                    "cap-" + offeringId);
            dsl.execute(
                    """
                    insert into sessions (tenant_id, id, offering_id, starts_at, ends_at, status, capacity,
                                          created_at, updated_at)
                    values (?, ?, ?, now(), now() + interval '1 hour', 'SCHEDULED', ?, now(), now())
                    """,
                    session.tenant().value(),
                    sessionId,
                    offeringId,
                    capacity);
            places.createPlaces(sessionId, POOL, capacity);
        }));
        return sessionId;
    }

    private int committed(Session session, UUID sessionId) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> units.committed(sessionId, POOL)));
    }

    private int remaining(Session session, UUID sessionId) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> units.remaining(sessionId, POOL)));
    }

    private int total(Session session, UUID sessionId) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> units.total(sessionId, POOL)));
    }

    private int rowsIn(Session session, UUID sessionId, UnitStatus status) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.fetchCount(
                CAPACITY_UNITS,
                CAPACITY_UNITS
                        .SESSION_ID
                        .eq(sessionId)
                        .and(CAPACITY_UNITS.POOL_CODE.eq(POOL))
                        .and(CAPACITY_UNITS.STATUS.eq(status.name())))));
    }
}
