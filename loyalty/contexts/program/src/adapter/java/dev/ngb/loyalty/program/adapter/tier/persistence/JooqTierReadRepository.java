package dev.ngb.loyalty.program.adapter.tier.persistence;

import static dev.ngb.loyalty_jooq.Tables.TIERS;

import dev.ngb.loyalty.program.api.tier.TierView;
import dev.ngb.loyalty.program.application.tier.query.TierReadRepository;
import dev.ngb.loyalty_jooq.tables.records.TiersRecord;
import java.util.List;
import org.jooq.DSLContext;

/** Selects the tier list in its stored order; the aggregate is never built on a read. */
public class JooqTierReadRepository implements TierReadRepository {

    private final DSLContext dsl;

    public JooqTierReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TierView> findAll() {
        return dsl.selectFrom(TIERS).orderBy(TIERS.POSITION).fetch(JooqTierReadRepository::toView);
    }

    private static TierView toView(TiersRecord row) {
        return new TierView(
                row.getCode(),
                row.getName(),
                row.getMinSpend(),
                row.getEarnMultiplier(),
                row.getPosition());
    }
}
