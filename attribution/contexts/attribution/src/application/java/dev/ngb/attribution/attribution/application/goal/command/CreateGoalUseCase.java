package dev.ngb.attribution.attribution.application.goal.command;

import dev.ngb.attribution.attribution.application.goal.port.GoalRepository;
import dev.ngb.attribution.attribution.domain.goal.Goal;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;

/** Creates a goal. Two goals in one tenant cannot share a key, because a snippet and a postback both name the key. */
public class CreateGoalUseCase {

    public record Command(String key, String name, BigDecimal defaultValue, String currency) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final GoalRepository goals;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateGoalUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            GoalRepository goals,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.goals = goals;
        this.ids = ids;
        this.clock = clock;
    }

    public Goal execute(Command command) {
        TenantId tenant = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            Goal goal = Goal.create(
                    ids.newId(), tenant, command.key(), command.name(), command.defaultValue(), command.currency(),
                    clock.now());
            if (goals.keyTaken(goal.key(), null)) {
                throw new ConflictException("A goal with that key already exists.");
            }
            goals.insert(goal);
            return goal;
        });
    }
}
