package dev.ngb.loyalty.points.adapter.member.persistence;

import static dev.ngb.loyalty_jooq.Tables.MEMBERS;
import static dev.ngb.loyalty_jooq.Tables.POINT_LOTS;

import dev.ngb.loyalty.points.application.member.query.ExpiringPoints;
import dev.ngb.loyalty.points.application.member.query.MemberReadRepository;
import dev.ngb.loyalty.points.application.member.query.MemberView;
import dev.ngb.loyalty.points.domain.member.MemberStatus;
import dev.ngb.loyalty_jooq.tables.records.MembersRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Selects a member and its expiring lots. The balance is summed here rather than stored: a lot that has expired is simply
 * not counted, so there is no total to keep in step with the ledger.
 */
public class JooqMemberReadRepository implements MemberReadRepository {

    private final DSLContext dsl;

    public JooqMemberReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<MemberView> find(String externalRef, Instant now) {
        Optional<MembersRecord> found = dsl.selectFrom(MEMBERS)
                .where(MEMBERS.EXTERNAL_REF.eq(externalRef))
                .and(MEMBERS.STATUS.eq(MemberStatus.ACTIVE.name()))
                .fetchOptional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MembersRecord row = found.get();
        long balance = longPoints(dsl.select(DSL.sum(POINT_LOTS.REMAINING))
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(row.getId()))
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .fetchOne(0, BigDecimal.class));
        return Optional.of(new MemberView(
                row.getId(),
                row.getExternalRef(),
                row.getDisplayName(),
                balance - row.getDebt(),
                row.getDebt(),
                row.getTierCode(),
                MemberStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                List.of()));
    }

    @Override
    public List<ExpiringPoints> expiring(UUID memberId, Instant now, Instant before) {
        return dsl.select(POINT_LOTS.EXPIRES_AT, DSL.sum(POINT_LOTS.REMAINING))
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.REMAINING.gt(0L))
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .and(POINT_LOTS.EXPIRES_AT.le(before))
                .groupBy(POINT_LOTS.EXPIRES_AT)
                .orderBy(POINT_LOTS.EXPIRES_AT)
                .fetch(row -> new ExpiringPoints(row.value1(), longPoints(row.value2())));
    }

    private static long longPoints(BigDecimal sum) {
        return sum == null ? 0L : sum.longValue();
    }
}
