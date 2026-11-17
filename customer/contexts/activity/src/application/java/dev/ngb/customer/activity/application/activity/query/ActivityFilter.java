package dev.ngb.customer.activity.application.activity.query;

import java.time.Instant;

/**
 * What a caller may narrow one customer's timeline by: the writer's own {@code type}, and a window of when the
 * activity happened. Every member is optional, and an absent one narrows nothing.
 */
public record ActivityFilter(String type, Instant from, Instant to) {

    public static ActivityFilter none() {
        return new ActivityFilter(null, null, null);
    }
}
