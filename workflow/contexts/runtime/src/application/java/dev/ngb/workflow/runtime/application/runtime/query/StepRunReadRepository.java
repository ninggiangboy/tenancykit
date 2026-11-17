package dev.ngb.workflow.runtime.application.runtime.query;

import java.util.List;
import java.util.UUID;

/** The read side of the step history: the redacted timeline an operator inspects. */
public interface StepRunReadRepository {

    /** Every recorded step of the instance, in the order it finished. */
    List<StepRunView> timeline(UUID instanceId);
}
