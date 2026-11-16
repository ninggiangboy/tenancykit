package dev.ngb.loyalty.rewards.adapter.reward.persistence;

import static dev.ngb.loyalty_jooq.Tables.REWARDS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;
import dev.ngb.loyalty_jooq.tables.records.RewardsRecord;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * The catalog entries this tenant writes.
 *
 * <p>Stock is taken and given back with one conditional update each, never with a read followed by a write: the
 * decrement asks the row for {@code stock > 0}, so the database decides which of two racing redemptions gets the last
 * unit, and the loser takes no row and lets its whole transaction unwind.
 */
public class JooqRewardRepository implements RewardRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqRewardRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public void add(Reward reward) {
        dsl.insertInto(REWARDS)
                .set(REWARDS.TENANT_ID, tenantId().value())
                .set(REWARDS.ID, reward.id().value())
                .set(REWARDS.NAME, reward.name())
                .set(REWARDS.DESCRIPTION, reward.description())
                .set(REWARDS.POINTS_COST, reward.pointsCost())
                .set(REWARDS.STOCK, reward.stock())
                .set(REWARDS.STATUS, reward.status().name())
                .set(REWARDS.VALID_FROM, reward.validFrom())
                .set(REWARDS.VALID_TO, reward.validTo())
                .set(REWARDS.VOUCHER_VALID_DAYS, reward.voucherValidDays())
                .set(REWARDS.VERSION, reward.version())
                .set(REWARDS.CREATED_AT, reward.createdAt())
                .set(REWARDS.UPDATED_AT, reward.updatedAt())
                .execute();
    }

    @Override
    public void update(Reward reward) {
        int updated = dsl.update(REWARDS)
                .set(REWARDS.NAME, reward.name())
                .set(REWARDS.DESCRIPTION, reward.description())
                .set(REWARDS.POINTS_COST, reward.pointsCost())
                .set(REWARDS.STOCK, reward.stock())
                .set(REWARDS.STATUS, reward.status().name())
                .set(REWARDS.VALID_FROM, reward.validFrom())
                .set(REWARDS.VALID_TO, reward.validTo())
                .set(REWARDS.VOUCHER_VALID_DAYS, reward.voucherValidDays())
                .set(REWARDS.UPDATED_AT, reward.updatedAt())
                .set(REWARDS.VERSION, reward.version())
                .where(REWARDS.ID.eq(reward.id().value()))
                .and(REWARDS.TENANT_ID.eq(tenantId().value()))
                .and(REWARDS.VERSION.lt(reward.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "reward " + reward.id() + " was not updated: version " + reward.version() + " is not newer");
        }
    }

    @Override
    public Optional<Reward> findById(RewardId id) {
        return dsl.selectFrom(REWARDS)
                .where(REWARDS.ID.eq(id.value()))
                .fetchOptional(JooqRewardRepository::toDomain);
    }

    @Override
    public Optional<Reward> lockById(RewardId id) {
        return dsl.selectFrom(REWARDS)
                .where(REWARDS.ID.eq(id.value()))
                .forNoKeyUpdate()
                .fetchOptional(JooqRewardRepository::toDomain);
    }

    @Override
    public boolean takeOne(RewardId id) {
        return dsl.update(REWARDS)
                        .set(REWARDS.STOCK, REWARDS.STOCK.minus(1))
                        .where(REWARDS.ID.eq(id.value()))
                        .and(REWARDS.TENANT_ID.eq(tenantId().value()))
                        .and(REWARDS.STOCK.gt(0L))
                        .execute()
                == 1;
    }

    @Override
    public void returnOne(RewardId id) {
        dsl.update(REWARDS)
                .set(REWARDS.STOCK, REWARDS.STOCK.plus(1))
                .where(REWARDS.ID.eq(id.value()))
                .and(REWARDS.TENANT_ID.eq(tenantId().value()))
                .and(REWARDS.STOCK.isNotNull())
                .execute();
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }

    private static Reward toDomain(RewardsRecord row) {
        return Reward.restore(
                new TenantId(row.getTenantId()),
                new RewardId(row.getId()),
                row.getName(),
                row.getDescription(),
                row.getPointsCost(),
                row.getStock(),
                RewardStatus.valueOf(row.getStatus()),
                row.getValidFrom(),
                row.getValidTo(),
                row.getVoucherValidDays(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
