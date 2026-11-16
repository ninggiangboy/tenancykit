package dev.ngb.loyalty.points.adapter.tier.persistence;

import static dev.ngb.loyalty_jooq.Tables.MEMBERS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_EARNS;

import dev.ngb.loyalty.points.api.tier.MemberTiers;
import dev.ngb.loyalty.points.application.tier.port.TierStore;
import dev.ngb.loyalty.points.domain.member.MemberStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The member row and the earn records as the tier evaluation reads them.
 *
 * <p>Qualifying spend is the net amount each order's earn record holds, never a table of its own: a return already lowered
 * the order's figure, so a window sum needs no special case for returns. The member row is written here and nowhere else
 * outside the enrolment and debt flows.
 */
public class JooqTierStore implements TierStore {

    private final DSLContext dsl;

    public JooqTierStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<MemberTiers.TierMember> lock(UUID memberId) {
        return dsl.select(MEMBERS.ID, MEMBERS.EXTERNAL_REF, MEMBERS.TIER_CODE, MEMBERS.TIER_REVIEW_AT)
                .from(MEMBERS)
                .where(MEMBERS.ID.eq(memberId))
                .forUpdate()
                .fetchOptional(row -> new MemberTiers.TierMember(
                        row.get(MEMBERS.ID),
                        row.get(MEMBERS.EXTERNAL_REF),
                        row.get(MEMBERS.TIER_CODE),
                        row.get(MEMBERS.TIER_REVIEW_AT)));
    }

    @Override
    public List<MemberTiers.SpendEntry> spendSince(UUID memberId, Instant after) {
        return dsl.select(ORDER_EARNS.OCCURRED_AT, ORDER_EARNS.SPEND_AMOUNT)
                .from(ORDER_EARNS)
                .where(ORDER_EARNS.MEMBER_ID.eq(memberId))
                .and(ORDER_EARNS.OCCURRED_AT.gt(after))
                .orderBy(ORDER_EARNS.OCCURRED_AT)
                .fetch(row -> new MemberTiers.SpendEntry(
                        row.get(ORDER_EARNS.OCCURRED_AT), row.get(ORDER_EARNS.SPEND_AMOUNT)));
    }

    @Override
    public void recordTier(UUID memberId, String tierCode, Instant reviewAt) {
        dsl.update(MEMBERS)
                .set(MEMBERS.TIER_CODE, tierCode)
                .set(MEMBERS.TIER_REVIEW_AT, reviewAt)
                .set(MEMBERS.VERSION, MEMBERS.VERSION.plus(1))
                .where(MEMBERS.ID.eq(memberId))
                .execute();
    }

    @Override
    public void setReviewAt(UUID memberId, Instant reviewAt) {
        dsl.update(MEMBERS)
                .set(MEMBERS.TIER_REVIEW_AT, reviewAt)
                .set(MEMBERS.VERSION, MEMBERS.VERSION.plus(1))
                .where(MEMBERS.ID.eq(memberId))
                .execute();
    }

    @Override
    public List<UUID> remarkForReview(Instant at, UUID afterId, int limit) {
        List<UUID> page = dsl.select(MEMBERS.ID)
                .from(MEMBERS)
                .where(MEMBERS.STATUS.eq(MemberStatus.ACTIVE.name()))
                .and(after(afterId))
                .orderBy(MEMBERS.ID)
                .limit(limit)
                .fetch(MEMBERS.ID);
        if (page.isEmpty()) {
            return page;
        }
        dsl.update(MEMBERS)
                .set(MEMBERS.TIER_REVIEW_AT, at)
                .set(MEMBERS.VERSION, MEMBERS.VERSION.plus(1))
                .where(MEMBERS.ID.in(page))
                // A member already due by this instant keeps the earlier one, so a run that stopped halfway continues
                // where it left off instead of moving the members it already reached.
                .and(MEMBERS.TIER_REVIEW_AT.isNull().or(MEMBERS.TIER_REVIEW_AT.gt(at)))
                .execute();
        return page;
    }

    @Override
    public List<UUID> dueForReview(Instant now, UUID afterId, int limit) {
        return dsl.select(MEMBERS.ID)
                .from(MEMBERS)
                .where(MEMBERS.STATUS.eq(MemberStatus.ACTIVE.name()))
                .and(MEMBERS.TIER_REVIEW_AT.lessOrEqual(now))
                .and(after(afterId))
                .orderBy(MEMBERS.ID)
                .limit(limit)
                .fetch(MEMBERS.ID);
    }

    private static Condition after(UUID afterId) {
        return afterId == null ? DSL.noCondition() : MEMBERS.ID.gt(afterId);
    }

    @Override
    public boolean holds(String tierCode) {
        return dsl.fetchExists(dsl.selectOne()
                .from(MEMBERS)
                .where(MEMBERS.TIER_CODE.eq(tierCode))
                .and(MEMBERS.STATUS.eq(MemberStatus.ACTIVE.name())));
    }
}
