package dev.ngb.loyalty.rewards.adapter.reward.persistence;

import static dev.ngb.loyalty_jooq.Tables.REWARDS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.loyalty.rewards.application.reward.query.RewardListing;
import dev.ngb.loyalty.rewards.application.reward.query.RewardReadRepository;
import dev.ngb.loyalty.rewards.application.reward.query.RewardView;
import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;
import dev.ngb.loyalty_jooq.tables.records.RewardsRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects catalog entries straight into the view; the aggregate is never built on a read. */
public class JooqRewardReadRepository implements RewardReadRepository {

    /** The reward fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(RewardListing.SCHEMA)
            .column("id", REWARDS.ID)
            .column("name", REWARDS.NAME)
            .column("points_cost", REWARDS.POINTS_COST)
            .column("stock", REWARDS.STOCK)
            .column("status", REWARDS.STATUS)
            .column("valid_from", REWARDS.VALID_FROM)
            .column("valid_to", REWARDS.VALID_TO)
            .column("created_at", REWARDS.CREATED_AT)
            .column("updated_at", REWARDS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    public JooqRewardReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<RewardView> list(ListQuery query, Boolean active) {
        Condition scope = active == null
                ? noCondition()
                : active
                        ? REWARDS.STATUS.eq(RewardStatus.ACTIVE.name())
                        : REWARDS.STATUS.ne(RewardStatus.ACTIVE.name());
        return JooqLists.fetch(dsl, REWARDS, scope, BINDING, query, JooqRewardReadRepository::toView);
    }

    @Override
    public Optional<RewardView> findById(UUID id) {
        return dsl.selectFrom(REWARDS).where(REWARDS.ID.eq(id)).fetchOptional(JooqRewardReadRepository::toView);
    }

    private static RewardView toView(RewardsRecord row) {
        RewardStatus status = RewardStatus.valueOf(row.getStatus());
        return new RewardView(
                row.getId(),
                row.getName(),
                row.getDescription(),
                row.getPointsCost(),
                row.getStock(),
                status == RewardStatus.ACTIVE,
                status,
                row.getValidFrom(),
                row.getValidTo(),
                row.getVoucherValidDays(),
                row.getVersion());
    }
}
