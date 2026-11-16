package dev.ngb.attribution.attribution.adapter.goal.persistence;

import static dev.ngb.attribution_jooq.Tables.GOALS;

import dev.ngb.attribution.attribution.application.goal.query.GoalReadRepository;
import dev.ngb.attribution.attribution.application.goal.query.GoalView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** The goal read side: a tenant's goals and one by id, selected as the aggregate the API and a replay both use. */
public class JooqGoalReadRepository implements GoalReadRepository {

    private final DSLContext dsl;

    public JooqGoalReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<GoalView> list() {
        return dsl.selectFrom(GOALS).fetch(this::toView);
    }

    @Override
    public Optional<GoalView> find(UUID goalId) {
        return dsl.selectFrom(GOALS).where(GOALS.ID.eq(goalId)).fetchOptional(this::toView);
    }

    private GoalView toView(dev.ngb.attribution_jooq.tables.records.GoalsRecord row) {
        return new GoalView(
                row.getId(),
                row.getKey(),
                row.getName(),
                row.getDefaultValue(),
                row.getCurrency(),
                row.getStatus(),
                row.getVersion());
    }
}
