package dev.ngb.booking.catalog.application.session.query;

/** One pool as a read returns it; {@code remaining} is how many places are still free. */
public record PoolView(String code, int capacity, int remaining) {}
