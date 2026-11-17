package dev.ngb.booking.booking.application.series.port;

import dev.ngb.booking.booking.domain.series.BookingSeries;
import java.util.Optional;
import java.util.UUID;

/** Stores the current tenant's series; every statement is narrowed by row-level security. */
public interface SeriesRepository {

    void insert(BookingSeries series);

    /** The series under a row lock, for a transition that must not race another caller. */
    Optional<BookingSeries> lockById(UUID seriesId);

    void update(BookingSeries series);
}
