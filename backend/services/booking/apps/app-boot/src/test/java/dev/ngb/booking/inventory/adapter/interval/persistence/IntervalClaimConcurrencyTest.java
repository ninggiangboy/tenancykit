package dev.ngb.booking.inventory.adapter.interval.persistence;

import static dev.ngb.booking_jooq.Tables.INTERVAL_CLAIMS;
import static dev.ngb.booking_jooq.Tables.RESOURCES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.Claimed;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.api.engine.IntervalClaim;
import dev.ngb.booking.support.BookingApiTest;
import dev.ngb.core.tenancy.RequestScope;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * The interval engine's promise under contention: an exclusion constraint, not an application check, is what keeps two
 * overlapping claims of one resource from both committing.
 */
class IntervalClaimConcurrencyTest extends BookingApiTest {

    @Autowired
    @Qualifier("intervalEngine")
    InventoryEngine engine;

    @Test
    void fiftyParallelOverlappingClaimsLeaveOneRow() throws Exception {
        Session session = tenant();
        UUID resource = seedResource(session);
        Instant start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(Duration.ofHours(1));
        RequestScope scope = RequestScope.tenant(session.tenant());

        int claims = 50;
        CountDownLatch ready = new CountDownLatch(claims);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(claims);
        List<Future<?>> futures = new ArrayList<>(claims);
        try {
            for (int i = 0; i < claims; i++) {
                UUID booking = UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        tenantContext.callAs(
                                scope,
                                () -> unitOfWork.transactional(
                                        () -> engine.claim(held(booking, resource, start, end))));
                    } catch (RuntimeException refused) {
                        // The loser's transaction is refused; the row count is what the test asserts.
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowsOf(session, INTERVAL_CLAIMS)).isEqualTo(1);
    }

    @Test
    void adjacentRangesBothSucceed() {
        Session session = tenant();
        UUID resource = seedResource(session);
        Instant start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        Instant middle = start.plus(Duration.ofHours(1));
        Instant end = start.plus(Duration.ofHours(2));

        assertThat(claim(session, resource, start, middle)).isInstanceOf(Claimed.class);
        assertThat(claim(session, resource, middle, end)).isInstanceOf(Claimed.class);
        assertThat(rowsOf(session, INTERVAL_CLAIMS)).isEqualTo(2);
    }

    @Test
    void theSameRangeOnTwoResourcesBothSucceed() {
        Session session = tenant();
        UUID first = seedResource(session);
        UUID second = seedResource(session);
        Instant start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(Duration.ofHours(1));

        assertThat(claim(session, first, start, end)).isInstanceOf(Claimed.class);
        assertThat(claim(session, second, start, end)).isInstanceOf(Claimed.class);
        assertThat(rowsOf(session, INTERVAL_CLAIMS)).isEqualTo(2);
    }

    private ClaimOutcome claim(Session session, UUID resource, Instant start, Instant end) {
        UUID booking = UUID.randomUUID();
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> engine.claim(held(booking, resource, start, end))));
    }

    private static IntervalClaim held(UUID booking, UUID resource, Instant start, Instant end) {
        Instant now = Instant.now();
        return new IntervalClaim(
                booking, resource, start, end, 0, 0, start.plus(Duration.ofDays(2)), now, 60);
    }

    private UUID seedResource(Session session) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            dsl.insertInto(RESOURCES)
                    .set(RESOURCES.TENANT_ID, session.tenant().value())
                    .set(RESOURCES.ID, id)
                    .set(RESOURCES.CODE, "r-" + id)
                    .set(RESOURCES.NAME, "Resource")
                    .set(RESOURCES.ZONE, "UTC")
                    .set(RESOURCES.STATUS, "ACTIVE")
                    .set(RESOURCES.VERSION, 1L)
                    .set(RESOURCES.CREATED_AT, now)
                    .set(RESOURCES.UPDATED_AT, now)
                    .execute();
        }));
        return id;
    }
}
