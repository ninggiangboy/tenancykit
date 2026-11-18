package dev.ngb.billing.coupon.adapter.coupon.persistence;

import static dev.ngb.billing_jooq.Tables.PROMOTION_CODES;

import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing_jooq.tables.records.PromotionCodesRecord;
import dev.ngb.core.id.TenantId;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

public class JooqPromotionCodeRepository implements PromotionCodeRepository {

    private final DSLContext dsl;

    public JooqPromotionCodeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(PromotionCode code) {
        dsl.insertInto(PROMOTION_CODES)
                .set(PROMOTION_CODES.ID, code.id().value())
                .set(PROMOTION_CODES.TENANT_ID, code.tenantId().value())
                .set(PROMOTION_CODES.COUPON_ID, code.couponId().value())
                .set(PROMOTION_CODES.CODE, code.code())
                .set(PROMOTION_CODES.MAX_REDEMPTIONS, code.maxRedemptions())
                .set(PROMOTION_CODES.REDEEM_BY, code.redeemBy())
                .set(PROMOTION_CODES.ACTIVE, code.isActive())
                .set(PROMOTION_CODES.CREATED_BY, code.createdBy())
                .set(PROMOTION_CODES.CREATED_AT, code.createdAt())
                .execute();
    }

    @Override
    public void update(PromotionCode code) {
        dsl.update(PROMOTION_CODES)
                .set(PROMOTION_CODES.ACTIVE, code.isActive())
                .where(PROMOTION_CODES.ID.eq(code.id().value()))
                .execute();
    }

    @Override
    public Optional<PromotionCode> findById(PromotionCodeId id) {
        return dsl.selectFrom(PROMOTION_CODES).where(PROMOTION_CODES.ID.eq(id.value())).fetchOptional(JooqPromotionCodeRepository::toDomain);
    }

    @Override
    public Optional<PromotionCode> findByCode(String code) {
        return dsl.selectFrom(PROMOTION_CODES).where(PROMOTION_CODES.CODE.eq(code)).fetchOptional(JooqPromotionCodeRepository::toDomain);
    }

    @Override
    public Optional<PromotionCode> lockByCode(String code) {
        return dsl.selectFrom(PROMOTION_CODES).where(PROMOTION_CODES.CODE.eq(code)).forUpdate()
                .fetchOptional(JooqPromotionCodeRepository::toDomain);
    }

    @Override
    public List<PromotionCode> findByCoupon(CouponId couponId) {
        return dsl.selectFrom(PROMOTION_CODES)
                .where(PROMOTION_CODES.COUPON_ID.eq(couponId.value()))
                .orderBy(PROMOTION_CODES.CREATED_AT, PROMOTION_CODES.ID)
                .fetch(JooqPromotionCodeRepository::toDomain);
    }

    @Override
    public boolean countRedemption(PromotionCodeId id) {
        return dsl.update(PROMOTION_CODES)
                .set(PROMOTION_CODES.REDEEMED_COUNT, PROMOTION_CODES.REDEEMED_COUNT.plus(1))
                .where(PROMOTION_CODES.ID.eq(id.value()))
                .and(PROMOTION_CODES.ACTIVE)
                .and(org.jooq.impl.DSL.or(PROMOTION_CODES.MAX_REDEMPTIONS.isNull(),
                        PROMOTION_CODES.REDEEMED_COUNT.lt(PROMOTION_CODES.MAX_REDEMPTIONS)))
                .execute() == 1;
    }

    private static PromotionCode toDomain(PromotionCodesRecord row) {
        return PromotionCode.restore(new PromotionCodeId(row.getId()), new TenantId(row.getTenantId()), new CouponId(row.getCouponId()),
                row.getCode(), row.getMaxRedemptions(), row.getRedeemBy(), row.getCreatedBy(), row.getCreatedAt(), row.getRedeemedCount(),
                row.getActive());
    }
}
