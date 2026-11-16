package dev.ngb.attribution.attribution.application.goal.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** The goal read side, opened in a unit of work so row-level security scopes the query to the caller's tenant. */
public class GoalQueries {

    private final UnitOfWork unitOfWork;
    private final GoalReadRepository goals;

    public GoalQueries(UnitOfWork unitOfWork, GoalReadRepository goals) {
        this.unitOfWork = unitOfWork;
        this.goals = goals;
    }

    public List<GoalView> list() {
        return unitOfWork.transactional(() -> goals.list().stream()
                .sorted(Comparator.comparing(GoalView::key))
                .toList());
    }

    public GoalView get(UUID goalId) {
        return unitOfWork.transactional(
                () -> goals.find(goalId).orElseThrow(() -> new NotFoundException("No such goal.")));
    }
}
