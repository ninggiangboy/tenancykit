package dev.ngb.booking.inventory.adapter.interval.persistence;

import static dev.ngb.booking_jooq.Tables.INTERVAL_CLAIMS;
import static dev.ngb.booking_jooq.Tables.RESOURCES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.Claimed;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.api.engine.IntervalClaim;
import dev.ngb.booking.support.BookingApiTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * When a hold stops blocking: inside its grace it refuses an overlapping claim, and past it the next claim reclaims it
 * and takes the range. The clock is the claim's own {@code now}, so the boundary is exact.
 */
class IntervalExpiryReclaimTest extends BookingApiTest {

    private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-06-01T11:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T12:00:00Z");
    private static final long GRACE_SECONDS = 60L;

    @Autowired
    @Qualifier("intervalEngine")
    InventoryEngine engine;

    @Test
    void aHoldInsideItsGraceBlocksAndOnePastItIsReclaimed() {
        Session session = tenant();
        UUID resource = seedResource(session);

        assertThat(claim(session, resource, EXPIRES_AT.minusSeconds(3600))).isInstanceOf(Claimed.class);
        assertThat(claim(session, resource, EXPIRES_AT.plusSeconds(30)))
                .as("a hold inside expires_at + grace still blocks")
                .isInstanceOf(Conflict.class);
        assertThat(rowsOf(session, INTERVAL_CLAIMS))
                .as("the refused claim changed nothing")
                .isEqualTo(1);

        assertThat(claim(session, resource, EXPIRES_AT.plusSeconds(120)))
                .as("a hold past expires_at + grace no longer blocks")
                .isInstanceOf(Claimed.class);
        assertThat(rowsOf(session, INTERVAL_CLAIMS)).isEqualTo(1);
    }

    private ClaimOutcome claim(Session session, UUID resource, Instant now) {
        UUID booking = UUID.randomUUID();
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> engine.claim(
                        new IntervalClaim(booking, resource, START, END, 0, 0, EXPIRES_AT, now, GRACE_SECONDS))));
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
