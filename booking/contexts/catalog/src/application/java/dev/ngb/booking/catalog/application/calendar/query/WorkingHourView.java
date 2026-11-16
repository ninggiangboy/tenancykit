package dev.ngb.booking.catalog.application.calendar.query;

/**
 * One window of a weekly pattern as a read returns it, in local wall-clock minutes from midnight. A read model carries
 * the numbers, so the calendar query never names a domain type.
 */
public record WorkingHourView(int weekday, int opensMinute, int closesMinute) {}
