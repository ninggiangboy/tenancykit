package dev.ngb.booking.catalog.api.calendar;

/** One weekly window, in local minutes from midnight; a close may be 1440, which is the end of the day. */
public record WorkingHourWindow(int weekday, int opensMinute, int closesMinute) {}
