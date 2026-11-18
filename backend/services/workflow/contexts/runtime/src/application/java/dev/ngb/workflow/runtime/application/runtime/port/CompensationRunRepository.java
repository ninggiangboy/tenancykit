package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.compensation.CompensationRun;

/** The compensation rows: one per compensated step, moved from RUNNING to its end by conditional updates. */
public interface CompensationRunRepository {

    void add(CompensationRun run);

    void update(CompensationRun run);
}
