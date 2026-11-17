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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The flash-sale promise under contention: a pool never holds or books more places than it has, however many callers
 * arrive at once.
 *
 * <p>A thousand claims of one place run against a hundred-place pool, three times over. Every answer is either a taken
 * place or a sold-out conflict, and the places are taken by the engine bean itself inside one tenant scope with one
 * transaction per caller, which is what a booking use case does. The lock is the row lock, so nothing outside the
 * database has to be trusted.
 */
class CapacityClaimConcurrencyTest extends BookingApiTest {

    private static final String POOL = "default";
    private static final int CAPACITY = 100;
    private static final int CLAIMS = 1_000;
    private static final int ROUNDS = 3;
    private static final int IN_FLIGHT = 24;

    /** The capacity engine among the app's engines; the interval one answers a different capability. */
    @Autowired
    List<InventoryEngine> engines;

    @Autowired
    CapacityUnitReader units;

    @Autowired
    SessionPlaces places;

    @Test
    void aThousandClaimsOfOnePlaceTakeExactlyTheHundredPlaces() throws Exception {
        Session session = tenant();

        for (int round = 0; round < ROUNDS; round++) {
            assertRound(session, seedSession(session, CAPACITY));
        }
    }

    private void assertRound(Session session, UUID sessionId) throws Exception {
        Instant now = Instant.now();
        Semaphore inFlight = new Semaphore(IN_FLIGHT);
        List<Future<ClaimOutcome>> answers = new ArrayList<>(CLAIMS);
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLAIMS; i++) {
                answers.add(callers.submit(claimOf(session, sessionId, now, inFlight)));
            }
            while (!answers.stream().allMatch(Future::isDone)) {
                assertThat(committed(session, sessionId))
                        .as("no moment holds or books more places than the pool has")
                        .isLessThanOrEqualTo(CAPACITY);
                Thread.sleep(1);
            }
        }

        List<ClaimOutcome> outcomes = new ArrayList<>(CLAIMS);
        for (Future<ClaimOutcome> answer : answers) {
            outcomes.add(answer.get());
        }

        assertThat(outcomes.stream().filter(Claimed.class::isInstance).count())
                .as("every place is taken exactly once")
                .isEqualTo(CAPACITY);
        assertThat(outcomes.stream().filter(Conflict.class::isInstance).count())
                .as("every other claim is refused")
                .isEqualTo(CLAIMS - CAPACITY);
        assertThat(outcomes.stream()
                        .filter(Conflict.class::isInstance)
                        .map(answer -> ((Conflict) answer).code())
                        .distinct()
                        .toList())
                .containsExactly(Conflict.SOLD_OUT);
        assertThat(outcomes.stream()
                        .filter(Conflict.class::isInstance)
                        .map(answer -> ((Conflict) answer).remaining())
                        .toList())
                .as("a refused claim is told how many places are still free")
                .allSatisfy(remaining -> assertThat(remaining).isNotNull().isGreaterThanOrEqualTo(0));

        assertThat(rowsIn(session, sessionId, UnitStatus.HELD)).isEqualTo(CAPACITY);
        assertThat(rowsIn(session, sessionId, UnitStatus.BOOKED)).isZero();
        assertThat(remaining(session, sessionId)).isZero();
        assertThat(total(session, sessionId)).isEqualTo(CAPACITY);
    }

    private Callable<ClaimOutcome> claimOf(Session session, UUID sessionId, Instant now, Semaphore inFlight) {
        CapacityClaim claim = new CapacityClaim(UUID.randomUUID(), sessionId, POOL, 1, now.plusSeconds(600), now, 30);
        return () -> {
            inFlight.acquire();
            try {
                return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> capacity().claim(claim)));
            } finally {
                inFlight.release();
            }
        };
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
                    values (?, ?, ?, 'Concurrency capacity', 'CAPACITY', 'ACTIVE', 60, 60, 90, now(), now())
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
            dsl.execute(
                    "insert into session_pools (tenant_id, session_id, code, capacity) values (?, ?, ?, ?)",
                    session.tenant().value(),
                    sessionId,
                    POOL,
                    capacity);
            places.createPlaces(sessionId, POOL, capacity);
        }));
        return sessionId;
    }

    private int committed(Session session, UUID sessionId) {
        return inTenant(
                session.tenant(), () -> unitOfWork.transactional(() -> units.committed(sessionId, POOL)));
    }

    private int remaining(Session session, UUID sessionId) {
        return inTenant(
                session.tenant(), () -> unitOfWork.transactional(() -> units.remaining(sessionId, POOL)));
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
