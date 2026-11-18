package dev.ngb.attribution.attribution.adapter.goal.persistence;

import static dev.ngb.attribution_jooq.Tables.GOALS;

import dev.ngb.attribution.attribution.application.goal.port.GoalRepository;
import dev.ngb.attribution.attribution.domain.goal.Goal;
import dev.ngb.attribution.attribution.domain.goal.GoalStatus;
import dev.ngb.core.id.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The goal store: the tenant's own goals, keyed by the key a snippet and a postback name. */
public class JooqGoalRepository implements GoalRepository {

    private final DSLContext dsl;

    public JooqGoalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Goal goal) {
        dsl.insertInto(GOALS)
                .set(GOALS.ID, goal.id())
                .set(GOALS.TENANT_ID, goal.tenantId().value())
                .set(GOALS.KEY, goal.key())
                .set(GOALS.NAME, goal.name())
                .set(GOALS.DEFAULT_VALUE, goal.defaultValue())
                .set(GOALS.CURRENCY, goal.currency())
                .set(GOALS.STATUS, goal.status().name())
                .set(GOALS.VERSION, goal.version())
                .set(GOALS.CREATED_AT, goal.createdAt())
                .set(GOALS.UPDATED_AT, goal.updatedAt())
                .execute();
    }

    @Override
    public Optional<Goal> findById(UUID goalId) {
        return dsl.selectFrom(GOALS).where(GOALS.ID.eq(goalId)).fetchOptional(this::toGoal);
    }

    @Override
    public Optional<Goal> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return dsl.selectFrom(GOALS)
                .where(GOALS.KEY.eq(key.strip().toLowerCase(java.util.Locale.ROOT)))
                .fetchOptional(this::toGoal);
    }

    @Override
    public boolean update(Goal goal, long expectedVersion) {
        return dsl.update(GOALS)
                        .set(GOALS.NAME, goal.name())
                        .set(GOALS.DEFAULT_VALUE, goal.defaultValue())
                        .set(GOALS.CURRENCY, goal.currency())
                        .set(GOALS.STATUS, goal.status().name())
                        .set(GOALS.VERSION, goal.version())
                        .set(GOALS.UPDATED_AT, goal.updatedAt())
                        .where(GOALS.ID.eq(goal.id()))
                        .and(GOALS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean keyTaken(String key, UUID exceptId) {
        var condition = DSL.selectOne().from(GOALS).where(GOALS.KEY.eq(key));
        if (exceptId != null) {
            condition = condition.and(GOALS.ID.ne(exceptId));
        }
        return dsl.fetchExists(condition);
    }

    private Goal toGoal(dev.ngb.attribution_jooq.tables.records.GoalsRecord row) {
        return new Goal(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getKey(),
                row.getName(),
                row.getDefaultValue(),
                row.getCurrency(),
                GoalStatus.valueOf(row.getStatus()),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
