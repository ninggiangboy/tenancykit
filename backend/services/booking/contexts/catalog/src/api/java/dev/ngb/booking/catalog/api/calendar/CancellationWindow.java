package dev.ngb.booking.catalog.api.calendar;

/**
 * One cancellation threshold: cancelling at least {@code minutesBeforeStart} before the start falls in this window,
 * and the booking reports the Tenant's own {@code code} rather than an amount.
 */
public record CancellationWindow(int minutesBeforeStart, String code) {}
