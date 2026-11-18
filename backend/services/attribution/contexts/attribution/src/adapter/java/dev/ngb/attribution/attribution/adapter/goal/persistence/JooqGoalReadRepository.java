package dev.ngb.attribution.attribution.adapter.goal.persistence;

import static dev.ngb.attribution_jooq.Tables.GOALS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.attribution.attribution.application.goal.query.GoalListing;
import dev.ngb.attribution.attribution.application.goal.query.GoalReadRepository;
import dev.ngb.attribution.attribution.application.goal.query.GoalView;
import dev.ngb.attribution_jooq.tables.records.GoalsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The goal read side: a page of a tenant's goals and one by id, selected as the aggregate the API and a replay both use.
 *
 * <p>The listing compiles the caller's {@link ListQuery} against the columns {@link GoalListing} whitelists. A goal is
 * reported by its key, and the default order is that key, so a reader finds the one they came for.
 */
public class JooqGoalReadRepository implements GoalReadRepository {

    /** The goal fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(GoalListing.SCHEMA)
            .column("id", GOALS.ID)
            .column("key", GOALS.KEY)
            .column("name", GOALS.NAME)
            .column("default_value", GOALS.DEFAULT_VALUE)
            .column("currency", GOALS.CURRENCY)
            .column("status", GOALS.STATUS)
            .column("created_at", GOALS.CREATED_AT)
            .column("updated_at", GOALS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    public JooqGoalReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** @return the binding of the goal listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    @Override
    public Page<GoalView> list(ListQuery query) {
        return JooqLists.fetch(dsl, GOALS, noCondition(), BINDING, query, JooqGoalReadRepository::toView);
    }

    @Override
    public Optional<GoalView> find(UUID goalId) {
        return dsl.selectFrom(GOALS).where(GOALS.ID.eq(goalId)).fetchOptional(JooqGoalReadRepository::toView);
    }

    private static GoalView toView(GoalsRecord row) {
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
