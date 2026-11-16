package dev.ngb.billing.coupon.adapter.coupon.persistence;

import static dev.ngb.billing_jooq.Tables.PROMOTION_CODES;

import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeReadRepository;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeView;
import dev.ngb.billing_jooq.tables.records.PromotionCodesRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects a promotion code straight into the view. */
public class JooqPromotionCodeReadRepository implements PromotionCodeReadRepository {

    private final DSLContext dsl;

    public JooqPromotionCodeReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PromotionCodeView> findById(UUID id) {
        return dsl.selectFrom(PROMOTION_CODES).where(PROMOTION_CODES.ID.eq(id))
                .fetchOptional(JooqPromotionCodeReadRepository::toView);
    }

    @Override
    public List<PromotionCodeView> findByCoupon(UUID couponId) {
        return dsl.selectFrom(PROMOTION_CODES)
                .where(PROMOTION_CODES.COUPON_ID.eq(couponId))
                .orderBy(PROMOTION_CODES.CREATED_AT, PROMOTION_CODES.ID)
                .fetch(JooqPromotionCodeReadRepository::toView);
    }

    private static PromotionCodeView toView(PromotionCodesRecord row) {
        return new PromotionCodeView(row.getId(), row.getCode(), row.getCouponId(), row.getMaxRedemptions(), row.getRedeemedCount(),
                row.getRedeemBy(), row.getActive(), row.getCreatedAt());
    }
}
