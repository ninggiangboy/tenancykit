package dev.ngb.booking.catalog.domain.calendar;

/**
 * What a dated exception does to a resource's weekly pattern: {@code CLOSED} closes every day of its range, and
 * {@code OPEN} replaces those days' hours with the ones it carries.
 */
public enum ExceptionKind {
    CLOSED,
    OPEN
}
