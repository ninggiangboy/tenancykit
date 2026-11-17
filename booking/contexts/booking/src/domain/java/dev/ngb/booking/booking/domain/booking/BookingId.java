package dev.ngb.booking.booking.domain.booking;

import java.util.Objects;
import java.util.UUID;

/** A booking's opaque id, and the key its claims in the inventory engines are held under. */
public record BookingId(UUID value) {

    public BookingId {
        Objects.requireNonNull(value, "value");
    }

    public static BookingId of(String text) {
        return new BookingId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
