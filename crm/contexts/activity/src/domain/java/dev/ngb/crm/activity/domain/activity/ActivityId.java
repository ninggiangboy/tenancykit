package dev.ngb.crm.activity.domain.activity;

import java.util.Objects;
import java.util.UUID;

/** crm's own opaque id for an activity; the caller's reference is an optional key and never the id. */
public record ActivityId(UUID value) {

    public ActivityId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
