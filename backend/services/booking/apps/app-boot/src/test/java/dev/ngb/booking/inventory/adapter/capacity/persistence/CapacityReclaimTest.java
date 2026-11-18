package dev.ngb.booking.inventory.adapter.capacity.persistence;

import static dev.ngb.booking_jooq.Tables.CAPACITY_UNITS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.booking.inventory.api.engine.CapacityClaim;
import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.Claimed;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import dev.ngb.booking.inventory.domain.capacity.UnitStatus;
import dev.ngb.booking.support.BookingApiTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A hold a booking never confirmed is freed by the claim path itself: inside its window it still blocks everybody, and
 * past its expiry plus the grace the next claim of that pool takes the place instead of adding one.
 */
class CapacityReclaimTest extends BookingApiTest {

    private static final String POOL = "default";
    private static final Instant NOW = Instant.parse("2026-05-04T09:00:00Z");
    private static final long HOLD_SECONDS = 300;
    private static final long GRACE_SECONDS = 30;

    @Autowired
    List<InventoryEngine> engines;

    @Autowired
    CapacityUnitReader units;

    @Autowired
    SessionPlaces places;

    @Test
    void aHoldInsideItsWindowStillBlocks() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 1);
        UUID first = UUID.randomUUID();
        assertThat(claimAt(session, sessionId, first, NOW)).isInstanceOf(Claimed.class);

        ClaimOutcome second = claimAt(session, sessionId, UUID.randomUUID(), NOW.plusSeconds(HOLD_SECONDS));

        assertThat(second).isInstanceOf(Conflict.class);
        assertThat(((Conflict) second).code()).isEqualTo(Conflict.SOLD_OUT);
        assertThat(((Conflict) second).remaining()).isZero();
        assertThat(heldBy(session, sessionId, first)).as("the hold is untouched").isEqualTo(1);
        assertThat(total(session, sessionId)).isEqualTo(1);
    }

    @Test
    void aLapsedHoldIsReclaimedByTheLazyPath() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 1);
        UUID first = UUID.randomUUID();
        assertThat(claimAt(session, sessionId, first, NOW)).isInstanceOf(Claimed.class);

        UUID second = UUID.randomUUID();
        ClaimOutcome reclaimed = claimAt(
                session, sessionId, second, NOW.plusSeconds(HOLD_SECONDS + GRACE_SECONDS + 1));

        assertThat(reclaimed).isInstanceOf(Claimed.class);
        assertThat(((Claimed) reclaimed).bookingId()).isEqualTo(second);
        assertThat(total(session, sessionId)).as("a reclaim frees a place instead of adding one").isEqualTo(1);
        assertThat(committed(session, sessionId)).isEqualTo(1);
        assertThat(heldBy(session, sessionId, first)).isZero();
        assertThat(heldBy(session, sessionId, second)).isEqualTo(1);
        assertThat(remaining(session, sessionId)).isZero();
    }

    @Test
    void aHoldExactlyAtItsGraceStillBlocks() {
        Session session = tenant();
        UUID sessionId = seedSession(session, 1);
        UUID first = UUID.randomUUID();
        assertThat(claimAt(session, sessionId, first, NOW)).isInstanceOf(Claimed.class);

        ClaimOutcome second =
                claimAt(session, sessionId, UUID.randomUUID(), NOW.plusSeconds(HOLD_SECONDS + GRACE_SECONDS));

        assertThat(second).as("the grace has to have passed, not merely been reached").isInstanceOf(Conflict.class);
        assertThat(heldBy(session, sessionId, first)).isEqualTo(1);
        assertThat(committed(session, sessionId)).isEqualTo(1);
    }

    private ClaimOutcome claimAt(Session session, UUID sessionId, UUID bookingId, Instant now) {
        CapacityClaim claim = new CapacityClaim(
                bookingId, sessionId, POOL, 1, NOW.plusSeconds(HOLD_SECONDS), now, GRACE_SECONDS);
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> capacity().claim(claim)));
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
                    values (?, ?, ?, 'Reclaim capacity', 'CAPACITY', 'ACTIVE', 60, 60, 90, now(), now())
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

    private int heldBy(Session session, UUID sessionId, UUID bookingId) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.fetchCount(
                CAPACITY_UNITS,
                CAPACITY_UNITS
                        .SESSION_ID
                        .eq(sessionId)
                        .and(CAPACITY_UNITS.POOL_CODE.eq(POOL))
                        .and(CAPACITY_UNITS.STATUS.eq(UnitStatus.HELD.name()))
                        .and(CAPACITY_UNITS.BOOKING_ID.eq(bookingId)))));
    }
}
