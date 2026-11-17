package dev.ngb.booking.booking.application.series.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads series straight into their views; the aggregate is never rebuilt for a read. */
public interface SeriesReadRepository {

    List<SeriesView> list();

    Optional<SeriesView> findById(UUID seriesId);
}
