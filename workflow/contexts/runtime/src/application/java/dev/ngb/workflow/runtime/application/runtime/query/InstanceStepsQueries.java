package dev.ngb.workflow.runtime.application.runtime.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** What an operator reads about one instance's steps. */
public class InstanceStepsQueries {

    private final UnitOfWork unitOfWork;
    private final StepRunReadRepository steps;

    public InstanceStepsQueries(UnitOfWork unitOfWork, StepRunReadRepository steps) {
        this.unitOfWork = unitOfWork;
        this.steps = steps;
    }

    public List<StepRunView> timeline(UUID instanceId) {
        return unitOfWork.transactional(() -> steps.timeline(instanceId));
    }
}
