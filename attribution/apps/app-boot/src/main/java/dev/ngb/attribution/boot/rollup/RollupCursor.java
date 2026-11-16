package dev.ngb.attribution.boot.rollup;

import java.time.LocalDate;

/**
 * How far the rollup has reached, which is what tells retention a raw day is safe to drop.
 *
 * <p>Retention cannot drop a day the rollups have not absorbed: a report reads rollups, and a day that is neither
 * rolled up nor still raw is a hole. Until the rollup phase lands, {@link #none()} answers with nothing and retention
 * drops nothing, which is the safe state a deployment starts in.
 */
@FunctionalInterface
public interface RollupCursor {

    /** The last UTC day the rollups have fully absorbed, or null when no rollup has run yet. */
    LocalDate rolledUpThrough();

    /** The state before any rollup exists: retention is inert rather than guessing. */
    static RollupCursor none() {
        return () -> null;
    }
}
