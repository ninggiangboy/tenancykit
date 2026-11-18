package dev.ngb.booking.catalog.domain.session;

import java.util.Objects;
import java.util.UUID;

/** A series' opaque id; the sessions a series materializes name it. */
public record SessionSeriesId(UUID value) {

    public SessionSeriesId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionSeriesId of(String text) {
        return new SessionSeriesId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
