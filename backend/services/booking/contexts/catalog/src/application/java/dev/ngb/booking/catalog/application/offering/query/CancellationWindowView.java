package dev.ngb.booking.catalog.application.offering.query;

/** One cancellation threshold of an offering as a read returns it: the Tenant's code and how long before the start. */
public record CancellationWindowView(int minutesBeforeStart, String code) {}
