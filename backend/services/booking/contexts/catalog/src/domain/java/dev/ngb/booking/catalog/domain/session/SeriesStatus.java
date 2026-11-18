package dev.ngb.booking.catalog.domain.session;

/** A series' lifecycle: a materialization job only expands the series that are active. */
public enum SeriesStatus {
    ACTIVE,
    ARCHIVED
}
