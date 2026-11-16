package dev.ngb.booking.catalog.domain.calendar;

import java.util.Objects;
import java.util.UUID;

/** A dated exception's opaque id, which a caller names to remove it. */
public record CalendarExceptionId(UUID value) {

    public CalendarExceptionId {
        Objects.requireNonNull(value, "value");
    }

    public static CalendarExceptionId of(String text) {
        return new CalendarExceptionId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
