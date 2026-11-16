package dev.ngb.loyalty.points.adapter.redemption.persistence;

import static dev.ngb.loyalty_jooq.Tables.MEMBERS;
import static dev.ngb.loyalty_jooq.Tables.POINT_LOTS;
import static dev.ngb.loyalty_jooq.Tables.REDEMPTIONS;

import dev.ngb.loyalty.points.application.redemption.query.RedemptionReadRepository;
import dev.ngb.loyalty.points.application.redemption.query.RedemptionView;
import dev.ngb.loyalty_jooq.tables.records.RedemptionsRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Selects one member's redemption together with the balance and the debt the lots give that member now.
 *
 * <p>The balance is summed here rather than stored, so a lot that expired between the spend and the read is simply not
 * counted and the answer needs nothing kept in step with the ledger.
 */
public class JooqRedemptionReadRepository implements RedemptionReadRepository {

    private final DSLContext dsl;

    public JooqRedemptionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<RedemptionView> find(UUID memberId, UUID redemptionId, Instant now) {
        Optional<RedemptionsRecord> found = dsl.selectFrom(REDEMPTIONS)
                .where(REDEMPTIONS.ID.eq(redemptionId))
                .and(REDEMPTIONS.MEMBER_ID.eq(memberId))
                .fetchOptional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RedemptionsRecord row = found.get();
        return dsl.select(MEMBERS.EXTERNAL_REF, MEMBERS.DEBT)
                .from(MEMBERS)
                .where(MEMBERS.ID.eq(memberId))
                .fetchOptional()
                .map(member -> new RedemptionView(
                        row.getId(),
                        member.value1(),
                        row.getPoints(),
                        row.getReference(),
                        row.getStatus(),
                        liveBalance(memberId, now) - member.value2(),
                        member.value2(),
                        row.getCreatedAt(),
                        row.getReversedAt()));
    }

    private long liveBalance(UUID memberId, Instant now) {
        BigDecimal sum = dsl.select(DSL.sum(POINT_LOTS.REMAINING))
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .fetchOne(0, BigDecimal.class);
        return sum == null ? 0L : sum.longValue();
    }
}
