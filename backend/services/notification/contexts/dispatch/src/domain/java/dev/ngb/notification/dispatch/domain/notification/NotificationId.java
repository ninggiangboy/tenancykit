package dev.ngb.notification.dispatch.domain.notification;

import java.util.Objects;
import java.util.UUID;

/** A notification's identity: a UUID v7, so the id is the list's sort key and cursor. */
public record NotificationId(UUID value) {

    public NotificationId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
