package dev.ngb.booking.webhook.adapter.delivery.web;

import static dev.ngb.booking_jooq.Tables.OUTBOX;
import static dev.ngb.booking_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingEvents;
import dev.ngb.booking.booking.domain.booking.BookingId;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import dev.ngb.core.event.EventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The outbox and the delivery it produces share one transaction: a use case that appends an event and then fails rolls
 * both back, so a fact nobody kept never becomes a webhook.
 */
class WebhookRollbackTest extends BookingApiTest {

    @Autowired
    EventPublisher events;

    @Test
    void anEventAppendedInARolledBackTransactionQueuesNothing() {
        Session session = tenant(BookingPermissions.WEBHOOK_MANAGE);
        Instant now = Instant.now();
        Booking booking = Booking.create(
                new BookingId(UUID.randomUUID()),
                session.tenant(),
                UUID.randomUUID(),
                "INTERVAL",
                "cust-rollback",
                1,
                null,
                null,
                now.plus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)),
                BookingSource.TENANT,
                now.plus(Duration.ofMinutes(10)),
                null,
                List.of(),
                null,
                null,
                now);

        assertThatThrownBy(() -> inTenant(
                        session.tenant(),
                        () -> unitOfWork.transactional(() -> {
                            events.publish(BookingEvents.held(UUID.randomUUID(), booking, now));
                            throw new IllegalStateException("the use case failed after appending");
                        })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(inTenant(
                                session.tenant(),
                                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(
                                        OUTBOX, OUTBOX.TYPE.eq("booking.held")))))
                .as("a rolled-back event leaves no outbox row")
                .isZero();
        assertThat(inTenant(
                                session.tenant(),
                                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(
                                        WEBHOOK_DELIVERIES,
                                        WEBHOOK_DELIVERIES.EVENT_TYPE.eq("booking.held")))))
                .as("and therefore no delivery row")
                .isZero();
    }
}
