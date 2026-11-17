package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.step.StepRun;

/** The append-only step history. */
public interface StepRunRepository {

    void add(StepRun run);
}
