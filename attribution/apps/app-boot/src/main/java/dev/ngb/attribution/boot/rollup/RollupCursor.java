package dev.ngb.attribution.boot.rollup;

import java.time.LocalDate;

/**
 * How far the rollup has reached, which is what tells retention a raw day is safe to drop.
 *
 * <p>Retention cannot drop a day the rollups have not absorbed: a report reads rollups, and a day that is neither
 * rolled up nor still raw is a hole. A null answer means no rollup has run yet, so retention drops nothing rather than
 * guessing.
 */
@FunctionalInterface
public interface RollupCursor {

    /** The last UTC day the rollups have fully absorbed, or null when no rollup has run yet. */
    LocalDate rolledUpThrough();
}
