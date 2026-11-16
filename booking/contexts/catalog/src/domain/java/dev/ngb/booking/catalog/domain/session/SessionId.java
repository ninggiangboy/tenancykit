package dev.ngb.booking.catalog.domain.session;

import java.util.Objects;
import java.util.UUID;

/** A session's opaque id. */
public record SessionId(UUID value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId of(String text) {
        return new SessionId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
