package dev.ngb.billing.coupon.adapter.redemption.persistence;

import static dev.ngb.billing_jooq.Tables.COUPON_REDEMPTIONS;
import static dev.ngb.billing_jooq.Tables.COUPONS;

import dev.ngb.billing.coupon.application.redemption.query.RedemptionReadRepository;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionView;
import dev.ngb.billing_jooq.tables.records.CouponRedemptionsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects a redemption straight into the view, joining the coupon only for the name it shows. */
public class JooqRedemptionReadRepository implements RedemptionReadRepository {

    private final DSLContext dsl;

    public JooqRedemptionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<RedemptionView> findById(UUID id) {
        return dsl.select(COUPON_REDEMPTIONS.asterisk(), COUPONS.NAME)
                .from(COUPON_REDEMPTIONS)
                .join(COUPONS).on(COUPONS.ID.eq(COUPON_REDEMPTIONS.COUPON_ID))
                .where(COUPON_REDEMPTIONS.ID.eq(id))
                .fetchOptional(record -> toView(record.into(COUPON_REDEMPTIONS), record.get(COUPONS.NAME)));
    }

    @Override
    public List<RedemptionView> findByWallet(UUID walletId, int limit, int offset) {
        return dsl.select(COUPON_REDEMPTIONS.asterisk(), COUPONS.NAME)
                .from(COUPON_REDEMPTIONS)
                .join(COUPONS).on(COUPONS.ID.eq(COUPON_REDEMPTIONS.COUPON_ID))
                .where(COUPON_REDEMPTIONS.WALLET_ID.eq(walletId))
                .orderBy(COUPON_REDEMPTIONS.REDEEMED_AT.desc(), COUPON_REDEMPTIONS.ID)
                .limit(limit)
                .offset(offset)
                .fetch(record -> toView(record.into(COUPON_REDEMPTIONS), record.get(COUPONS.NAME)));
    }

    private static RedemptionView toView(CouponRedemptionsRecord row, String couponName) {
        return new RedemptionView(
                row.getId(),
                row.getCouponId(),
                couponName,
                row.getCodeId(),
                row.getWalletId(),
                row.getCustomerId(),
                row.getStatus(),
                row.getMaxApplications(),
                row.getApplicationsUsed(),
                row.getMaxTotalDiscount(),
                row.getDiscountTotal(),
                row.getCurrency().strip(),
                row.getRedeemedAt(),
                row.getEndsAt(),
                row.getEndReason(),
                row.getEndedAt());
    }
}
