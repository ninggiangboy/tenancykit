package dev.ngb.billing.coupon.adapter.coupon.persistence;

import static dev.ngb.billing_jooq.Tables.COUPONS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.billing.coupon.application.coupon.query.CouponReadRepository;
import dev.ngb.billing.coupon.application.coupon.query.CouponView;
import dev.ngb.billing_jooq.tables.records.CouponsRecord;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects a coupon straight into the view; the aggregate is never built. */
public class JooqCouponReadRepository implements CouponReadRepository {

    private final DSLContext dsl;

    public JooqCouponReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<CouponView> findById(UUID id) {
        return dsl.selectFrom(COUPONS).where(COUPONS.ID.eq(id)).fetchOptional(JooqCouponReadRepository::toView);
    }

    @Override
    public List<CouponView> list(String status, int limit, int offset) {
        Condition filter = status == null ? noCondition() : COUPONS.STATUS.eq(status);
        return dsl.selectFrom(COUPONS)
                .where(filter)
                .orderBy(COUPONS.CREATED_AT.desc(), COUPONS.ID)
                .limit(limit)
                .offset(offset)
                .fetch(JooqCouponReadRepository::toView);
    }

    private static CouponView toView(CouponsRecord row) {
        return new CouponView(
                row.getId(),
                row.getName(),
                row.getBenefitType(),
                row.getPercentBasisPoints(),
                row.getAmountOff(),
                row.getCreditAmount(),
                row.getCurrency().strip(),
                row.getRedeemBy(),
                row.getMaxRedemptions(),
                row.getMaxPerCustomer(),
                row.getRedemptionsCount(),
                row.getBudget(),
                row.getBudgetUsed(),
                row.getStackable(),
                row.getStatus(),
                row.getCreatedBy(),
                row.getCreatedAt(),
                row.getDeactivatedAt(),
                row.getScopePlanIds() == null ? List.of() : Arrays.asList(row.getScopePlanIds()));
    }
}
