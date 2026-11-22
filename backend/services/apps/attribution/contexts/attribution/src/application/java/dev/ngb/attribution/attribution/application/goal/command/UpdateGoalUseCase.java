package dev.ngb.attribution.attribution.application.goal.command;

import dev.ngb.attribution.attribution.application.goal.port.GoalRepository;
import dev.ngb.attribution.attribution.domain.goal.Goal;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.math.BigDecimal;
import java.util.UUID;

/** Edits or archives a goal under {@code If-Match}, so a stale edit is refused rather than silently overwriting. */
public class UpdateGoalUseCase {

    public record Change(String name, BigDecimal defaultValue, String currency) {}

    private final UnitOfWork unitOfWork;
    private final GoalRepository goals;
    private final Clock clock;

    public UpdateGoalUseCase(UnitOfWork unitOfWork, GoalRepository goals, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.goals = goals;
        this.clock = clock;
    }

    public Goal execute(UUID goalId, Change change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Goal goal = goals.findById(goalId).orElseThrow(() -> new NotFoundException("No such goal."));
            expected.check(goal.version());
            Goal updated = goal.update(
                    change.name() == null ? goal.name() : change.name(),
                    change.defaultValue() == null ? goal.defaultValue() : change.defaultValue(),
                    change.currency() == null ? goal.currency() : change.currency(),
                    clock.now());
            if (!goals.update(updated, goal.version())) {
                throw new PreconditionFailedException(goal.version());
            }
            return updated;
        });
    }

    public Goal archive(UUID goalId) {
        return unitOfWork.transactional(() -> {
            Goal goal = goals.findById(goalId).orElseThrow(() -> new NotFoundException("No such goal."));
            Goal archived = goal.archive(clock.now());
            goals.update(archived, goal.version());
            return archived;
        });
    }
}
