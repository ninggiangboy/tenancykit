package dev.ngb.booking.booking.domain.series;

/** Whether a series books every occurrence or only the ones that do not conflict. */
public enum SeriesMode {
    ALL_OR_NOTHING,
    SKIP_CONFLICTS
}
