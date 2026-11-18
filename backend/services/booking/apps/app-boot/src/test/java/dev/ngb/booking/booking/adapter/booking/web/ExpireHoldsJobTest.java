package dev.ngb.booking.booking.adapter.booking.web;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.INTERVAL_CLAIMS;
import static dev.ngb.booking_jooq.Tables.OUTBOX;
import static dev.ngb.booking_jooq.Tables.RESOURCES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.booking.application.booking.command.ExpireHoldsUseCase;
import dev.ngb.booking.support.BookingApiTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The expiry sweep over a booking whose hold lapsed: it moves the booking to {@code EXPIRED}, releases the claim and
 * announces one fact. The booking is seeded directly, because no endpoint can create a hold that lapsed in the past.
 */
class ExpireHoldsJobTest extends BookingApiTest {

    @Autowired
    ExpireHoldsUseCase expirations;

    @Test
    void aLapsedHoldIsExpiredAndItsClaimReleased() {
        Session session = tenant();
        UUID bookingId = UUID.randomUUID();
        Instant now = Instant.now();

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            UUID resourceId = UUID.randomUUID();
            dsl.insertInto(RESOURCES)
                    .set(RESOURCES.TENANT_ID, session.tenant().value())
                    .set(RESOURCES.ID, resourceId)
                    .set(RESOURCES.CODE, "expire-room-" + resourceId)
                    .set(RESOURCES.NAME, "Room")
                    .set(RESOURCES.ZONE, "UTC")
                    .set(RESOURCES.STATUS, "ACTIVE")
                    .set(RESOURCES.VERSION, 1L)
                    .set(RESOURCES.CREATED_AT, now)
                    .set(RESOURCES.UPDATED_AT, now)
                    .execute();
            dsl.insertInto(BOOKINGS)
                    .set(BOOKINGS.TENANT_ID, session.tenant().value())
                    .set(BOOKINGS.ID, bookingId)
                    .set(BOOKINGS.OFFERING_ID, UUID.randomUUID())
                    .set(BOOKINGS.OFFERING_KIND, "INTERVAL")
                    .set(BOOKINGS.CUSTOMER_REF, "cust-expire")
                    .set(BOOKINGS.STATUS, "HELD")
                    .set(BOOKINGS.SOURCE, "TENANT")
                    .set(BOOKINGS.HOLD_EXPIRES_AT, now.minusSeconds(600))
                    .set(BOOKINGS.VERSION, 1L)
                    .set(BOOKINGS.CREATED_AT, now.minusSeconds(1200))
                    .set(BOOKINGS.UPDATED_AT, now.minusSeconds(1200))
                    .execute();
            dsl.execute(
                    """
                    insert into interval_claims (tenant_id, id, resource_id, booking_id, during, expires_at, created_at)
                    values (?, ?, ?, ?, tstzrange(?::timestamptz, ?::timestamptz, '[)'), ?::timestamptz, ?::timestamptz)
                    """,
                    session.tenant().value(),
                    UUID.randomUUID(),
                    resourceId,
                    bookingId,
                    OffsetDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(now.plusSeconds(7200), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(now.minusSeconds(600), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(now.minusSeconds(1200), ZoneOffset.UTC));
        }));

        inTenant(session.tenant(), () -> {
            expirations.execute();
        });

        assertThat(count(session, BOOKINGS.ID.eq(bookingId).and(BOOKINGS.STATUS.eq("EXPIRED"))))
                .as("the booking is expired")
                .isEqualTo(1);
        assertThat(countTable(session, INTERVAL_CLAIMS, INTERVAL_CLAIMS.BOOKING_ID.eq(bookingId)))
                .as("the claim is released")
                .isZero();
        assertThat(countTable(session, OUTBOX, OUTBOX.TYPE.eq("booking.expired")))
                .as("one expiry announces one fact")
                .isEqualTo(1);
    }

    private long count(Session session, org.jooq.Condition condition) {
        return countTable(session, BOOKINGS, condition);
    }

    private long countTable(Session session, org.jooq.Table<?> table, org.jooq.Condition condition) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(table, condition)));
    }
}
