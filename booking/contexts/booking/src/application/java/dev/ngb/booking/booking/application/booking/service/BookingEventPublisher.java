package dev.ngb.booking.booking.application.booking.service;

import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingEvents;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.IdGenerator;
import java.time.Instant;

/**
 * Appends the booking-state events to the outbox in the caller's transaction, so a rollback sends nothing.
 *
 * <p>The event id is generated here rather than by the aggregate, because the identifier is a fact of the announcement
 * and not of the booking: a booking that is confirmed twice announces one confirmation.
 */
public class BookingEventPublisher {

    private final EventPublisher events;
    private final IdGenerator ids;

    public BookingEventPublisher(EventPublisher events, IdGenerator ids) {
        this.events = events;
        this.ids = ids;
    }

    public void held(Booking booking, Instant occurredAt) {
        events.publish(BookingEvents.held(ids.newId(), booking, occurredAt));
    }

    public void confirmed(Booking booking, Instant occurredAt) {
        events.publish(BookingEvents.confirmed(ids.newId(), booking, occurredAt));
    }

    public void cancelled(Booking booking, Instant occurredAt) {
        events.publish(BookingEvents.cancelled(ids.newId(), booking, occurredAt));
    }

    public void expired(Booking booking, Instant occurredAt) {
        events.publish(BookingEvents.expired(ids.newId(), booking, occurredAt));
    }

    public void rescheduled(Booking booking, BookingEvents.Place previous, Instant occurredAt) {
        events.publish(BookingEvents.rescheduled(ids.newId(), booking, previous, occurredAt));
    }

    public void noShow(Booking booking, Instant occurredAt) {
        events.publish(BookingEvents.noShow(ids.newId(), booking, occurredAt));
    }
}
