package dev.ngb.loyalty.program.adapter.tier.persistence;

import static dev.ngb.loyalty_jooq.Tables.TIERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.program.application.tier.port.TierRepository;
import dev.ngb.loyalty.program.domain.tier.Tier;
import dev.ngb.loyalty.program.domain.tier.TierId;
import dev.ngb.loyalty_jooq.tables.records.TiersRecord;
import java.util.List;
import org.jooq.DSLContext;

public class JooqTierRepository implements TierRepository {

    private final DSLContext dsl;

    public JooqTierRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Tier> findAll() {
        return dsl.selectFrom(TIERS)
                .orderBy(TIERS.POSITION)
                .fetch(JooqTierRepository::toDomain);
    }

    @Override
    public void add(Tier t) {
        dsl.insertInto(TIERS)
                .set(TIERS.TENANT_ID, t.tenantId().value())
                .set(TIERS.ID, t.id().value())
                .set(TIERS.CODE, t.code())
                .set(TIERS.NAME, t.name())
                .set(TIERS.MIN_SPEND, t.minSpend())
                .set(TIERS.EARN_MULTIPLIER, t.earnMultiplier())
                .set(TIERS.POSITION, t.position())
                .execute();
    }

    @Override
    public void update(Tier t) {
        dsl.update(TIERS)
                .set(TIERS.NAME, t.name())
                .set(TIERS.MIN_SPEND, t.minSpend())
                .set(TIERS.EARN_MULTIPLIER, t.earnMultiplier())
                .set(TIERS.POSITION, t.position())
                .where(TIERS.ID.eq(t.id().value()))
                .and(TIERS.TENANT_ID.eq(t.tenantId().value()))
                .execute();
    }

    @Override
    public void delete(TierId id) {
        dsl.deleteFrom(TIERS).where(TIERS.ID.eq(id.value())).execute();
    }

    private static Tier toDomain(TiersRecord row) {
        return new Tier(
                new TenantId(row.getTenantId()),
                new TierId(row.getId()),
                row.getCode(),
                row.getName(),
                row.getMinSpend(),
                row.getEarnMultiplier(),
                row.getPosition());
    }
}
