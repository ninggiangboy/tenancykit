package dev.ngb.attribution.insight.api.rollup;

import java.time.Instant;
import java.time.LocalDate;

/**
 * How far the rollups have reached, which is what the retention job reads and what a report names as {@code data_through}.
 *
 * <p>Wired in the app as the worker's {@code RollupCursor}, so the retention job drops a raw partition only after the
 * rollups have absorbed its whole day.
 */
public interface RollupStateReader {

    /** The last UTC day every rollup has fully absorbed, or null when no rollup has run yet. */
    LocalDate rolledUpThrough();

    /** When the last rollup run finished, or null when none has run; a report shows it as its freshness. */
    Instant dataThrough();
}
